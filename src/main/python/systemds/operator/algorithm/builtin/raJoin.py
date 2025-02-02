# -------------------------------------------------------------
#
# Licensed to the Apache Software Foundation (ASF) under one
# or more contributor license agreements.  See the NOTICE file
# distributed with this work for additional information
# regarding copyright ownership.  The ASF licenses this file
# to you under the Apache License, Version 2.0 (the
# "License"); you may not use this file except in compliance
# with the License.  You may obtain a copy of the License at
#
#   http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing,
# software distributed under the License is distributed on an
# "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
# KIND, either express or implied.  See the License for the
# specific language governing permissions and limitations
# under the License.
#
# -------------------------------------------------------------

# Autogenerated By   : src/main/python/generator/generator.py
# Autogenerated From : scripts/builtin/raJoin.dml

from typing import Dict, Iterable

from systemds.operator import OperationNode, Matrix, Frame, List, MultiReturn, Scalar
from systemds.utils.consts import VALID_INPUT_TYPES


def raJoin(A: Matrix,
           colA: int,
           B: Matrix,
           colB: int,
           method: str):
    """
     This raJoin-function takes two matrix datasets as input from where it performs
     relational operations : join
    
    
    
    :param A: Matrix of left input data [shape: N x M]
    :param colA: Integer indicating the column index of matrix A to execute inner join command
    :param B: Matrix of right left data [shape: N x M]
    :param colA: Integer indicating the column index of matrix B to execute inner join command
    :param method: Join implementation method (nested-loop, sort-merge, hash, hash2)
    :return: Matrix of joined data [shape N' x M] with N' <= N
    """

    params_dict = {'A': A, 'colA': colA, 'B': B, 'colB': colB, 'method': method}
    return Matrix(A.sds_context,
        'raJoin',
        named_input_nodes=params_dict)
