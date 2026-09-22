# 传染病情景推演与参数校准

基于离散时间随机 SEIR 模型的本地可运行推演服务：支持传播情景建模与逐时推进、
批量随机重复实验（串行/并行结果一致）、基于观测数据的参数校准与不确定度输出。

## 快速开始

```bash
# 环境：JDK 21+，Maven 3.9+
mvn test          # 运行全部测试（37 个）
mvn spring-boot:run   # 启动本地服务，默认 8080 端口
```

### REST 接口

```bash
# 批量重复实验
curl -X POST localhost:8080/api/simulations/batch -H 'Content-Type: application/json' -d '{
  "scenario": {"name":"demo","population":2000,"initialExposed":5,"initialInfectious":5,
    "betaPerStep":0.05,"sigmaPerStep":0.2,"gammaPerStep":0.1,"steps":120,
    "interventions":[{"id":"lockdown","type":"TRANSMISSION_REDUCTION",
      "startStep":30,"endStep":60,"efficacy":0.5}]},
  "replications": 16, "baseSeed": 42, "parallelism": 4, "timeout": "PT2M"
}'

# 参数校准
curl -X POST localhost:8080/api/calibrations -H 'Content-Type: application/json' -d '{
  "scenario": { ...同上... },
  "observations": [{"step":10,"observedInfected":12},{"step":20,"observedInfected":30},
                   {"step":30,"observedInfected":55}],
  "betaLow": 0.01, "betaHigh": 0.1, "gridPoints": 10,
  "replicationsPerPoint": 8, "rmseTolerance": 500.0, "timeout": "PT5M"
}'
```

## 模型假设

- 时间步为**小时**，情景从 step 0 的初始状态推进，共产生 `steps` 个快照。
- 人群划分为 S（易感）/E（暴露）/I（感染）/R（康复）四仓，每步三条独立二项转移：
  - `newExposures ~ Binomial(S, betaEff · I / N)`，其中 `betaEff = betaPerStep × 干预乘子`
  - `newInfections ~ Binomial(E, sigmaPerStep)`（E→I）
  - `newRecovered  ~ Binomial(I, gammaPerStep)`（I→R）
- 所有速率参数（beta/sigma/gamma）均为**每步概率**，取值 [0,1]。
- 二项抽样按固定顺序逐个伯努利试验实现，只依赖 RNG 随机流，与执行方式无关。

## 干预窗口规则

- 窗口统一为**左闭右开** `[startStep, endStep)`：`startStep` 当刻生效，`endStep` 当刻失效。
- `TRANSMISSION_REDUCTION`：窗口内传播率乘上 `(1 - efficacy)`；多个此类干预重叠时
  **乘子相乘**（独立折减的合取解释），顺序无关。
- `VACCINATION`：在自己的 `startStep` 时刻一次性施加，将当时易感者的 `efficacy`
  比例免疫；同一时刻多个疫苗干预**按 id 升序**依次施加，顺序确定。
- 非法配置一律拒绝，绝不静默接受：
  - 空窗口/倒置窗口（`endStep <= startStep`）、窗口超出推演域、负 `startStep`；
  - `efficacy` 越界或非有限值；传播折减 `efficacy = 0` 视为自相矛盾的空配置；
  - **干预 id 重复**视为冲突配置（`CONFLICTING_INTERVENTIONS`），必须显式合并后重提。

## 可复现性约定（种子与并行度）

- 第 i 次重复实验的种子为 `replicateSeed = SplitMix64(baseSeed + φ·(i+1))`，
  只取决于 `(baseSeed, i)`，与并行度、任务划分、完成顺序无关。
- RNG 固定使用 JDK 内置 `L64X128MixRandom` 算法，不依赖平台默认实现。
- 聚合（逐时刻均值、25%/75% 分位数区间、峰值统计）按 `replicateIndex` 升序进行，
  内部拷贝排序，因此**同一 baseSeed 在任意 parallelism 下结果逐位一致**
  （`BatchResult` 不含执行方式元数据，可直接做相等断言）。
- 取消/超时整体生效：立即取消全部未完成任务并关闭执行器，抛出 `TIMEOUT`/`CANCELLED`
  异常，不返回部分结果，不留下后台线程。

## 失败原因分类

所有业务失败抛出带 `FailureReason` 的 `SimulationException`，REST 层映射为
`{"reason": ..., "message": ...}`，客户端可按 `reason` 精确区分：

| reason | HTTP | 含义 |
|---|---|---|
| `INVALID_SCENARIO` | 422 | 情景参数非法（人口、速率、步数等） |
| `INVALID_INTERVENTION` | 422 | 单条干预字段非法（窗口、强度、id） |
| `CONFLICTING_INTERVENTIONS` | 422 | 干预 id 重复等冲突配置 |
| `INVALID_CALIBRATION_REQUEST` | 422 | 校准请求非法（搜索区间、容差、观测点越界） |
| `INSUFFICIENT_DATA` | 422 | 观测点为空或少于 `epidemic.min-observations` |
| `NO_CONVERGENCE` | 422 | 网格内最优 RMSE 仍超过 `rmseTolerance` |
| `RESOURCE_LIMIT_EXCEEDED` | 413 | 超出人口/步数/重复次数/并行度/网格点/超时上限 |
| `TIMEOUT` | 504 | 超过请求时限，已整体取消 |
| `CANCELLED` | 409 | 线程被中断取消 |

资源上限在 `application.properties` 的 `epidemic.*` 下配置，超出即拒绝并说明原因。

## 校准方法

对 `betaPerStep` 在 `[betaLow, betaHigh]` 上等距网格搜索：每个网格点用固定种子
（`0xC0FFEE5EED`，同一请求重复校准结论一致）跑 `replicationsPerPoint` 次重复实验，
以观测时刻的均值曲线计算 RMSE；最优点的 RMSE 超过 `rmseTolerance` 时返回
`NO_CONVERGENCE`。不确定度以高斯权重 `w = exp(-rmse² / (2·bestRmse²))` 归一化后
给出加权均值（点估计）与 5%/95% 加权分位数区间。

## 本地验证

```bash
mvn test
```

测试覆盖（断言均按 `FailureReason` 区分，日志打印种子/并行度/聚合结果）：

- `ScenarioValidatorTest`：非法与冲突配置拒绝（13 例）；
- `InterventionScheduleTest`：窗口边界 `[start,end)`、重叠相乘、疫苗时序与同刻排序；
- `BatchRunnerTest`：同一种子在 parallelism=1/2/3/4 下结果逐位一致、不同种子结果不同、
  超时/中断整体取消且无后台线程残留、资源上限拒绝；
- `CalibrationServiceTest`：成功校准与不确定度区间、数据不足、非法区间、
  无法收敛、资源超限、超时传播；
- `ApplicationTests`：Spring 上下文加载。
