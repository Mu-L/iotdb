# Licensed to the Apache Software Foundation (ASF) under one
# or more contributor license agreements.  See the NOTICE file
# distributed with this work for additional information
# regarding copyright ownership.  The ASF licenses this file
# to you under the Apache License, Version 2.0 (the
# "License"); you may not use this file except in compliance
# with the License.  You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing,
# software distributed under the License is distributed on an
# "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
# KIND, either express or implied.  See the License for the
# specific language governing permissions and limitations
# under the License.
#
import inspect
import logging
import os
from enum import Enum
from typing import List

from ainode.thrift.common.ttypes import TEndPoint

AINODE_VERSION_INFO = "UNKNOWN"
AINODE_BUILD_INFO = "UNKNOWN"
AINODE_CONF_DIRECTORY_NAME = "conf"
AINODE_ROOT_CONF_DIRECTORY_NAME = "conf"
AINODE_CONF_FILE_NAME = "iotdb-ainode.properties"
AINODE_CONF_GIT_FILE_NAME = "git.properties"
AINODE_CONF_POM_FILE_NAME = "pom.properties"
AINODE_SYSTEM_FILE_NAME = "system.properties"

# AINode cluster configuration
AINODE_CLUSTER_NAME = "defaultCluster"
AINODE_TARGET_CONFIG_NODE_LIST = TEndPoint("127.0.0.1", 10710)
AINODE_RPC_ADDRESS = "127.0.0.1"
AINODE_RPC_PORT = 10810
AINODE_CLUSTER_INGRESS_ADDRESS = "127.0.0.1"
AINODE_CLUSTER_INGRESS_PORT = 6667
AINODE_CLUSTER_INGRESS_USERNAME = "root"
AINODE_CLUSTER_INGRESS_PASSWORD = "root"
AINODE_CLUSTER_INGRESS_TIME_ZONE = "UTC+8"

# RPC config
AINODE_THRIFT_COMPRESSION_ENABLED = False
DEFAULT_RECONNECT_TIMEOUT = 20
DEFAULT_RECONNECT_TIMES = 3

# AINode inference configuration
AINODE_INFERENCE_BATCH_INTERVAL_IN_MS = 15
AINODE_INFERENCE_MAX_PREDICT_LENGTH = 2880

# AINode folder structure
AINODE_ROOT_DIR = os.path.dirname(
    os.path.dirname(os.path.abspath(inspect.getfile(inspect.currentframe())))
)
AINODE_MODELS_DIR = "data/ainode/models"
AINODE_BUILTIN_MODELS_DIR = "data/ainode/models/weights"  # For built-in models, we only need to store their weights and config.
AINODE_SYSTEM_DIR = "data/ainode/system"
AINODE_LOG_DIR = "logs"

# AINode log
LOG_FILE_TYPE = ["all", "info", "warning", "error"]
AINODE_LOG_FILE_NAME_PREFIX = "log_ainode_"
AINODE_LOG_FILE_NAMES = [
    "log_ainode_all.log",
    "log_ainode_info.log",
    "log_ainode_warning.log",
    "log_ainode_error.log",
]
AINODE_LOG_FILE_LEVELS = [logging.DEBUG, logging.INFO, logging.WARNING, logging.ERROR]
DEFAULT_LOG_LEVEL = logging.INFO
INFERENCE_LOG_FILE_NAME_PREFIX_TEMPLATE = (
    "log_inference_rank_{}_"  # example: log_inference_rank_0_all.log
)

# AINode model management
MODEL_WEIGHTS_FILE_IN_SAFETENSORS = "model.safetensors"
MODEL_CONFIG_FILE_IN_JSON = "config.json"
DEFAULT_MODEL_FILE_NAME = "model.pt"
DEFAULT_CONFIG_FILE_NAME = "config.yaml"
DEFAULT_CHUNK_SIZE = 8192


class TSStatusCode(Enum):
    SUCCESS_STATUS = 200
    REDIRECTION_RECOMMEND = 400
    AINODE_INTERNAL_ERROR = 1510
    INVALID_URI_ERROR = 1511
    INVALID_INFERENCE_CONFIG = 1512
    INFERENCE_INTERNAL_ERROR = 1520

    def get_status_code(self) -> int:
        return self.value


class TaskType(Enum):
    FORECAST = "forecast"


class OptionsKey(Enum):
    # common
    TASK_TYPE = "task_type"
    MODEL_TYPE = "model_type"
    AUTO_TUNING = "auto_tuning"
    INPUT_VARS = "input_vars"

    # forecast
    INPUT_LENGTH = "input_length"
    PREDICT_LENGTH = "predict_length"
    PREDICT_INDEX_LIST = "predict_index_list"
    INPUT_TYPE_LIST = "input_type_list"

    def name(self) -> str:
        return self.value


class HyperparameterName(Enum):
    # Training hyperparameter
    LEARNING_RATE = "learning_rate"
    EPOCHS = "epochs"
    BATCH_SIZE = "batch_size"
    USE_GPU = "use_gpu"
    NUM_WORKERS = "num_workers"

    # Structure hyperparameter
    KERNEL_SIZE = "kernel_size"
    INPUT_VARS = "input_vars"
    BLOCK_TYPE = "block_type"
    D_MODEL = "d_model"
    INNER_LAYERS = "inner_layer"
    OUTER_LAYERS = "outer_layer"

    def name(self):
        return self.value


class ForecastModelType(Enum):
    DLINEAR = "dlinear"
    DLINEAR_INDIVIDUAL = "dlinear_individual"
    NBEATS = "nbeats"

    @classmethod
    def values(cls) -> List[str]:
        values = []
        for item in list(cls):
            values.append(item.value)
        return values


class ModelInputName(Enum):
    DATA_X = "data_x"
    TIME_STAMP_X = "time_stamp_x"
    TIME_STAMP_Y = "time_stamp_y"
    DEC_INP = "dec_inp"


class AttributeName(Enum):
    # forecast Attribute
    PREDICT_LENGTH = "predict_length"

    # NaiveForecaster
    STRATEGY = "strategy"
    SP = "sp"

    # STLForecaster
    # SP = 'sp'
    SEASONAL = "seasonal"
    SEASONAL_DEG = "seasonal_deg"
    TREND_DEG = "trend_deg"
    LOW_PASS_DEG = "low_pass_deg"
    SEASONAL_JUMP = "seasonal_jump"
    TREND_JUMP = "trend_jump"
    LOSS_PASS_JUMP = "low_pass_jump"

    # ExponentialSmoothing
    DAMPED_TREND = "damped_trend"
    INITIALIZATION_METHOD = "initialization_method"
    OPTIMIZED = "optimized"
    REMOVE_BIAS = "remove_bias"
    USE_BRUTE = "use_brute"

    # Arima
    ORDER = "order"
    SEASONAL_ORDER = "seasonal_order"
    METHOD = "method"
    MAXITER = "maxiter"
    SUPPRESS_WARNINGS = "suppress_warnings"
    OUT_OF_SAMPLE_SIZE = "out_of_sample_size"
    SCORING = "scoring"
    WITH_INTERCEPT = "with_intercept"
    TIME_VARYING_REGRESSION = "time_varying_regression"
    ENFORCE_STATIONARITY = "enforce_stationarity"
    ENFORCE_INVERTIBILITY = "enforce_invertibility"
    SIMPLE_DIFFERENCING = "simple_differencing"
    MEASUREMENT_ERROR = "measurement_error"
    MLE_REGRESSION = "mle_regression"
    HAMILTON_REPRESENTATION = "hamilton_representation"
    CONCENTRATE_SCALE = "concentrate_scale"

    # GAUSSIAN_HMM
    N_COMPONENTS = "n_components"
    COVARIANCE_TYPE = "covariance_type"
    MIN_COVAR = "min_covar"
    STARTPROB_PRIOR = "startprob_prior"
    TRANSMAT_PRIOR = "transmat_prior"
    MEANS_PRIOR = "means_prior"
    MEANS_WEIGHT = "means_weight"
    COVARS_PRIOR = "covars_prior"
    COVARS_WEIGHT = "covars_weight"
    ALGORITHM = "algorithm"
    N_ITER = "n_iter"
    TOL = "tol"
    PARAMS = "params"
    INIT_PARAMS = "init_params"
    IMPLEMENTATION = "implementation"

    # GMMHMM
    # N_COMPONENTS = "n_components"
    N_MIX = "n_mix"
    # MIN_COVAR = "min_covar"
    # STARTPROB_PRIOR = "startprob_prior"
    # TRANSMAT_PRIOR = "transmat_prior"
    WEIGHTS_PRIOR = "weights_prior"

    # MEANS_PRIOR = "means_prior"
    # MEANS_WEIGHT = "means_weight"
    # ALGORITHM = "algorithm"
    # COVARIANCE_TYPE = "covariance_type"
    # N_ITER = "n_iter"
    # TOL = "tol"
    # INIT_PARAMS = "init_params"
    # PARAMS = "params"
    # IMPLEMENTATION = "implementation"

    # STRAY
    ALPHA = "alpha"
    K = "k"
    KNN_ALGORITHM = "knn_algorithm"
    P = "p"
    SIZE_THRESHOLD = "size_threshold"
    OUTLIER_TAIL = "outlier_tail"

    # timerxl
    INPUT_TOKEN_LEN = "input_token_len"
    HIDDEN_SIZE = "hidden_size"
    INTERMEDIATE_SIZE = "intermediate_size"
    OUTPUT_TOKEN_LENS = "output_token_lens"
    NUM_HIDDEN_LAYERS = "num_hidden_layers"
    NUM_ATTENTION_HEADS = "num_attention_heads"
    HIDDEN_ACT = "hidden_act"
    USE_CACHE = "use_cache"
    ROPE_THETA = "rope_theta"
    ATTENTION_DROPOUT = "attention_dropout"
    INITIALIZER_RANGE = "initializer_range"
    MAX_POSITION_EMBEDDINGS = "max_position_embeddings"
    CKPT_PATH = "ckpt_path"

    # sundial
    DROPOUT_RATE = "dropout_rate"
    FLOW_LOSS_DEPTH = "flow_loss_depth"
    NUM_SAMPLING_STEPS = "num_sampling_steps"
    DIFFUSION_BATCH_MUL = "diffusion_batch_mul"

    def name(self) -> str:
        return self.value
