/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.sysds.runtime.compress.lib;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.sysds.conf.ConfigurationManager;
import org.apache.sysds.runtime.DMLRuntimeException;
import org.apache.sysds.runtime.compress.CompressedMatrixBlock;
import org.apache.sysds.runtime.compress.colgroup.AColGroup;
import org.apache.sysds.runtime.compress.colgroup.ColGroupDDC;
import org.apache.sysds.runtime.compress.colgroup.dictionary.IDictionary;
import org.apache.sysds.runtime.compress.colgroup.dictionary.IdentityDictionary;
import org.apache.sysds.runtime.compress.colgroup.indexes.ColIndexFactory;
import org.apache.sysds.runtime.compress.colgroup.indexes.IColIndex;
import org.apache.sysds.runtime.compress.colgroup.mapping.AMapToData;
import org.apache.sysds.runtime.compress.colgroup.mapping.MapToFactory;
import org.apache.sysds.runtime.matrix.data.LibMatrixReorg;
import org.apache.sysds.runtime.matrix.data.MatrixBlock;
import org.apache.sysds.runtime.util.CommonThreadPool;
import org.apache.sysds.runtime.util.UtilFunctions;

public final class CLALibRexpand {
	public static boolean ALLOW_COMPRESSED_TABLE_SEQ = false;
	protected static final Log LOG = LogFactory.getLog(CLALibRexpand.class.getName());

	private CLALibRexpand(){
		// private constructor
	}

	public static MatrixBlock rexpand(CompressedMatrixBlock in, MatrixBlock ret, double max, boolean rows, boolean cast,
		boolean ignore, int k) {
		if(rows)
			return in.getUncompressed("Rexpand in rows direction (one hot encode)").rexpandOperations(ret, max, rows, cast,
				ignore, k);
		else
			return rexpandCols(in, max, cast, ignore, k);
	}

	public static MatrixBlock rexpand(int seqHeight, MatrixBlock A) {
		return rexpand(seqHeight, A, -1);
	}

	public static MatrixBlock rexpand(int seqHeight, MatrixBlock A, int nColOut) {
		return rexpand(seqHeight, A, nColOut, 1);
	}

	public static MatrixBlock rexpand(int seqHeight, MatrixBlock A, int nColOut, int k) {

		try {
			final int[] map = new int[seqHeight];
			int maxCol = constructInitialMapping(map, A, k);
			boolean containsNull = maxCol < 0;
			maxCol = Math.abs(maxCol);

			if(nColOut == -1)
				nColOut = maxCol;
			else if(nColOut < maxCol)
				throw new DMLRuntimeException("invalid nColOut, requested: " + nColOut + " but have to be : " + maxCol);

			final int nNulls = containsNull ? correctNulls(map, nColOut) : 0;
			if(nColOut == 0) // edge case of empty zero dimension block.
				return new MatrixBlock(seqHeight, 0, 0.0);
			return createCompressedReturn(map, nColOut, seqHeight, nNulls, containsNull, k);
		}
		catch(Exception e) {
			throw new RuntimeException("Failed table seq operator", e);
		}
	}


	private static MatrixBlock rexpandCols(CompressedMatrixBlock in, double max, boolean cast, boolean ignore, int k) {
		return rexpandCols(in, UtilFunctions.toInt(max), cast, ignore, k);
	}

	private static MatrixBlock rexpandCols(CompressedMatrixBlock in, int max, boolean cast, boolean ignore, int k) {
		LibMatrixReorg.checkRexpand(in, ignore);

		final int nRows = in.getNumRows();
		if(in.isEmptyBlock(false))
			return new MatrixBlock(nRows, max, true);
		else if(in.isOverlapping() || in.getColGroups().size() > 1)
			return LibMatrixReorg.rexpand(in.getUncompressed("Rexpand (one hot encode)"), new MatrixBlock(), max, false,
				cast, ignore, k);
		else {
			CompressedMatrixBlock retC = new CompressedMatrixBlock(nRows, max);
			retC.allocateColGroup(in.getColGroups().get(0).rexpandCols(max, ignore, cast, nRows));
			retC.recomputeNonZeros();
			return retC;
		}
	}



	private static CompressedMatrixBlock createCompressedReturn(int[] map, int nColOut, int seqHeight, int nNulls,
		boolean containsNull, int k) throws Exception {
		// create a single DDC Column group.
		final IColIndex i = ColIndexFactory.create(0, nColOut);
		final IDictionary d = IdentityDictionary.create(nColOut, containsNull);
		final AMapToData m = MapToFactory.create(seqHeight, map, nColOut + (containsNull ? 1 : 0), k);
		final AColGroup g = ColGroupDDC.create(i, d, m, null);

		final CompressedMatrixBlock cmb = new CompressedMatrixBlock(seqHeight, nColOut);
		cmb.allocateColGroup(g);
		cmb.setNonZeros(seqHeight - nNulls);
		return cmb;
	}

	private static int correctNulls(int[] map, int nColOut) {
		int nNulls = 0;
		for(int i = 0; i < map.length; i++) {
			if(map[i] == -1) {
				map[i] = nColOut;
				nNulls++;
			}
		}
		return nNulls;
	}

	private static int constructInitialMapping(int[] map, MatrixBlock A, int k) {
		if(A.isEmpty() || A.isInSparseFormat())
			throw new DMLRuntimeException("not supported empty or sparse construction of seq table");
		final MatrixBlock Ac;
		if(A instanceof CompressedMatrixBlock) {
			// throw new NotImplementedException();
			LOG.warn("Decompression of right side input to CLALibTable, please implement alternative.");
			Ac = ((CompressedMatrixBlock) A).getUncompressed("rexpand", k);
		}
		else
			Ac = A;

		ExecutorService pool = CommonThreadPool.get(k);
		try {

			int blkz = Math.max((map.length / k), 1000);
			List<Future<Integer>> tasks = new ArrayList<>();
			for(int i = 0; i < map.length; i += blkz) {
				final int start = i;
				final int end = Math.min(i + blkz, map.length);
				tasks.add(pool.submit(() -> partialMapping(map, Ac, start, end)));
			}

			int maxCol = 0;
			for(Future<Integer> f : tasks) {
				int tmp = f.get();
				if(Math.abs(tmp) > Math.abs(maxCol))
					maxCol = tmp;
			}
			return maxCol;
		}
		catch(Exception e) {
			throw new DMLRuntimeException(e);
		}
		finally {
			pool.shutdown();
		}

	}

	private static int partialMapping(int[] map, MatrixBlock A, int start, int end) {

		int maxCol = 0;
		boolean containsNull = false;

		final double[] aVals = A.getDenseBlockValues();

		for(int i = start; i < end; i++) {
			final double v2 = aVals[i];
			if(Double.isNaN(v2)) {
				map[i] = -1; // assign temporarily to -1
				containsNull = true;
			}
			else {
				// safe casts to long for consistent behavior with indexing
				int col = UtilFunctions.toInt(v2);
				if(col <= 0)
					throw new DMLRuntimeException(
						"Erroneous input while computing the contingency table (value <= zero): " + v2);

				map[i] = col - 1;
				// maintain max seen col
				maxCol = Math.max(col, maxCol);
			}
		}

		return containsNull ? maxCol * -1 : maxCol;
	}

	public static boolean compressedTableSeq() {
		return ALLOW_COMPRESSED_TABLE_SEQ || ConfigurationManager.isCompressionEnabled();
	}
}
