# Epidemic Scenario Simulation & Parameter Calibration

一个可在本地完整运行的传染病情景推演与参数校准服务（Spring Boot, Java 21）。
同一情景在串行与并行执行下给出**可复现、可解释且一致**的结论。

## 模型假设

- **仓室模型**：封闭人群 SIR，`N = S + I + R` 恒定，无出生、死亡与迁入迁出。
- **时间步**：统一的逐小时推进，小时编号为 `0 .. horizonHours-1`。
- **随机机制**（每小时 `t`）：
  - `betaEff(t) = beta × ∏(生效的传播类干预乘子)`
  - `gammaEff(t) = min(gamma + Σ(生效的治疗类干预增量), 1)`
  - `newInfections ~ Binomial(S, 1 - exp(-betaEff · I / N))`
  - `newRecoveries ~ Binomial(I, 1 - exp(-gammaEff))`
- **二项抽样**：小均值精确模拟（掷币 / Knuth Poisson 近似），大均值正态近似；
  分支选择只取决于 `(n, p)`，与调度无关。

## 干预窗口规则

- 窗口为**半开区间** `[startHour, endHour)`：`startHour` 当小时生效，`endHour` 当小时起失效。
- 合法窗口须满足 `0 <= startHour < endHour <= horizonHours`。
- **同类型**干预窗口重叠（含完全重复）视为自相矛盾，拒绝并报 `CONFLICTING_INTERVENTIONS`；
  相邻窗口（如 `[10,20)` 与 `[20,30)`）不视为重叠。
- **不同类型**干预可自由重叠：传播类（SOCIAL_DISTANCING / MASKING）效果**相乘**，
  治疗类（TREATMENT）效果**相加**。
- 非法参数（非正人群、非法速率、越界效果值、空窗口等）一律抛出
  `InvalidScenarioException` 并携带机器可区分的 `Reason`，不会被静默接受。

## 可复现性约定（种子与并行度）

- 批次请求携带 `masterSeed`；每个重复实验的种子在**任务调度之前**按索引顺序串行派生，
  因此第 `k` 次重复的种子与并行度、任务划分无关。
- 聚合（均值、2.5%/97.5% 百分位区间）按**重复索引顺序**进行；百分位采用线性插值
  （R type-7 约定）。
- 结论：同一 `(scenario, replications, masterSeed)` 在任意 `parallelism` 下，
  逐次结果与聚合结果**完全一致**（测试中以 0 容差断言）。
- 日志会打印 `masterSeed`、`parallelism`、`replications` 与聚合结果等关键输入输出。

## 取消与超时的收尾语义

批量重复（`BatchRunner`，被 `/api/simulation/batch` 与校准内部调用）对**显式取消**
（调用线程被中断，例如上层超时或连接关闭）与**整体超时**（`timeoutMillis` 到期）
提供如下保证：

- **返回即静默（quiescence）**：`run(...)` 一旦返回——无论是正常返回还是抛出
  `BatchCancelledException`——属于该批次的**所有 worker 线程都已真正终止**，不存在任何
  还在后台占 CPU 的计算。收尾流程会先 `cancel(true)` + `shutdownNow()` 中断全部在跑与排队
  的任务，再**不可中断地**等待线程池终止并逐个 `join` worker 直到 `Thread.isAlive()==false`；
  等待期间即使调用线程再次收到中断也不会提前返回（中断状态会在收尾结束后原样恢复）。
  每个批次使用独立的、命名为 `batch-worker-<批次号>` 的守护线程池，批次结束池即销毁，
  因此连续取消只会看到线程随批次快速消失，不会堆积。
- **整体失败，绝无部分结果**：取消或超时都抛出 `BatchCancelledException`，已完成的重复
  结果一律丢弃，不返回聚合。
- **两类失败严格区分且稳定**：异常的 `isTimeout()` 为 `true` 当且仅当是超时；显式中断
  一律是取消（`isTimeout()==false`），**即使调用进入时中断标志已置位、或中断与 deadline
  到期同时发生，取消也优先，绝不会把取消误报成超时或反之**。HTTP 层据此分别返回
  `{"error":"CANCELLED"}` 与 `{"error":"TIMEOUT"}`（均为 408）。
- **串行 / 并行行为一致**：`parallelism == 1` 的串行路径（不建池，同一线程内协作式
  检查中断）与并行路径遵守完全相同的取消/超时分类与整体失败语义；并行下无论并发度
  （2..16）多大、取消发生在任务排队、部分运行还是收尾阶段，收尾都干净。
- **取消后无残留状态**：`BatchRunner` 不保留跨批次状态；一次取消返回后再发起正常批次，
  既不会失败，也不会串入上一批的种子、结果或线程。

## 校准不确定度：带噪声观测下的区间

校准最小化观测曲线与均值模拟曲线的 SSE；不确定度采用 **SSE 轮廓似然区间**（profile
likelihood），而不是在最优点数值求曲率：

- 阈值：`SSE(β) = SSE(β*) + q·σ²`，其中 `q = 3.84`（单参数 95% 卡方阈值）。
- 残差方差 `σ² = max(观测残差 SSE, 蒙特卡洛噪声地板)/(n-1)`；噪声地板由最优点处各观测
  小时跨重复的方差无偏估计给出。因此**模拟自身的随机噪声不可能把区间压成一个点**，
  带噪声观测会得到如实反映波动范围的区间，且噪声越大区间越宽。
- 两个端点用「几何外扩 + 定次二分」求交点，过程只依赖确定的目标函数，故对同一请求
  **任意重复、任意并行度都给出逐位相同的点估计与区间**。

成功结果额外返回 `noiseSigma`（残差噪声水平，单位为每小时感染人数），可用来判断区间
宽度是否与观测噪声相称。

## 校准失败原因分类

校准结果永不返回空结果或伪装成功；失败时 `failureReason` 取以下之一：

| 原因 | 含义 |
|---|---|
| `PARAMETER_OUT_OF_BOUNDS` | 参数边界非法（如 `0 < betaLower < betaUpper` 不成立）或基准情景非法 |
| `INSUFFICIENT_DATA` | 观测点不足（< 5）、含非法值或长度超过情景时域 |
| `NON_CONVERGENCE` | 评估预算耗尽仍未收敛（连不确定度区间都没算完） |
| `PARAMETER_UNIDENTIFIABLE` | 数据撑不起有意义的区间：最优点被压到参数边界、SSE 在边界内始终达不到 95% 阈值（区间不闭合）、或残差方差为 0 |
| `RESOURCE_LIMIT_EXCEEDED` | 请求超出资源上限，**在执行前拒绝**；`detailReason` 给出具体超限维度（见下） |

优化方法：网格粗搜 + 黄金分割细化；每次目标评估使用同一 `masterSeed`，整个校准对同一
请求完全可复现，且与并行度无关。

## 统一容量上限（外部可配）

所有真正吃算力的入口——单次推演 `POST /api/simulation/run`、批量重复
`POST /api/simulation/batch`、参数校准 `POST /api/calibration/beta`——都在**开始任何
计算之前**（派生种子、创建线程池、调度任何任务之前）经过同一个容量守卫校验。任一维度
超限即拒绝：不会跑一半、不会返回空结果、不会在后台继续跑。

受管维度（均为含边界，等于上限视为合法）：

| 维度（机器可读原因） | 配置键 | 默认上限 | 适用入口 |
|---|---|---|---|
| `POPULATION` | `app.capacity.max-population` | 1,000,000 | 全部 |
| `HORIZON_HOURS` | `app.capacity.max-horizon-hours` | 50,000 小时 | 全部 |
| `REPLICATIONS` | `app.capacity.max-replications` | 1,000 | 批量、校准（每次评估的重复数） |
| `PARALLELISM` | `app.capacity.max-parallelism` | 16 | 批量、校准 |
| `CALIBRATION_EVALUATIONS` | `app.capacity.max-calibration-evaluations` | 500 | 校准 |
| `TOTAL_SIMULATED_HOURS` | `app.capacity.max-total-simulated-hours` | 20,000,000 | 批量：重复×时域；校准：评估×重复×时域 |

本地无需改代码即可调大调小，任选一种 Spring Boot 标准方式覆盖（配置在
`src/main/resources/application.properties`）：

```bash
# 环境变量
APP_CAPACITY_MAX_REPLICATIONS=4000 mvn spring-boot:run
# JVM 系统属性 / 命令行参数
java -jar app.jar --app.capacity.max-replications=4000
mvn spring-boot:run -Dspring-boot.run.arguments=--app.capacity.max-total-simulated-hours=50000000
```

### 超限响应如何区分

- 单次推演 / 批量：HTTP **422**，响应体
  `{"error":"RESOURCE_LIMIT_EXCEEDED","reason":"<维度>","dimension":"<维度>",
  "requested":<实际值>,"limit":<上限>,"message":...}`。
  `reason` 即上表的维度枚举名，程序可直接据此分支处理。
- 校准：HTTP **422**，响应体 `success=false`、
  `failureReason="RESOURCE_LIMIT_EXCEEDED"`、`detailReason="<维度>"`、
  `evaluationsUsed=0`（一个评估都不会跑）；失败结果里的估计值字段统一序列化为
  JSON `null`（不会是字符串 `"NaN"`）。
- 情景本身非法（与容量无关，如人群为 0、速率非法、窗口冲突）仍返回 HTTP **400** 并带
  原有 `reason`（如 `INVALID_POPULATION`），不会与容量拒绝混淆。

## REST API

- `POST /api/simulation/run` — 单次情景随机推演，返回该种子下的轨迹（逐小时感染数、峰值、最终康复数）。
- `POST /api/simulation/batch` — 批量随机重复实验，返回聚合结果；
  非法配置 → 400（含 `reason`），超容量 → 422（含维度原因），取消/超时 → 408。
- `POST /api/calibration/beta` — 参数校准；失败 → 422（含 `failureReason`，容量超限另有 `detailReason`）。

## 日志可观测性

关键输入输出都会进入日志，便于事后核对：

- 单次推演：`seed`、`population`、`horizonHours`、`peakInfected`、`totalRecovered`。
- 批次：`masterSeed`、`parallelism`、`replications`、请求规模（人群、时域）、
  聚合值（`meanPeakInfected`、`peakP2.5/P97.5`、`meanTotalRecovered`）。
- 校准：`masterSeed`、`parallelism`、`replications`、观测数、边界、评估预算、
  `estimatedBeta`、`CI95=[lo, hi]` 及区间宽度、`sse`、`noiseSigma`、`evaluations`；
  被拒绝时记录 `reason/detail/message`。

## 本地验证

```bash
mvn test                 # 全部自动化测试（76 个）
mvn spring-boot:run      # 启动服务（默认 8080）

# 另一个终端：端到端 HTTP 走查（正常推演 / 批量串并行一致 / 带噪声校准 /
# 三类容量拒绝 / 非法请求 400），应输出 “RESULT pass=14 fail=0”
bash scripts/e2e.sh
# 端口不是 8080 时：E2E_BASE=http://localhost:8081 bash scripts/e2e.sh

# 验证外部容量覆盖（不改代码，把重复数上限调成 3）
APP_CAPACITY_MAX_REPLICATIONS=3 mvn spring-boot:run

# 只跑取消/超时收尾相关用例（确定性栅栏，在 worker 运行中触发取消）
mvn -Dtest=BatchCancellationTest test
# 既有的取消/超时与串并行一致性用例
mvn -Dtest=BatchRunnerTest test
```

如何在本地复现「取消返回瞬间后台已无计算线程」：

- 自动化方式：`BatchCancellationTest` 通过包内可见的测试插桩点
  （`BatchRunner.replicationHook`，生产代码恒为 `null`）把每个 worker 在**刚进入某次
  重复计算时**挂在一个确定性栅栏上，然后中断调用线程；断言的是 `run()` 返回的那一帧
  所捕获到的本批 worker 线程**逐个 `isAlive()==false`**（不是靠事后轮询宽限），并覆盖：
  - 并行度 2 / 8 / 16 下在运行中取消，返回瞬间本批 worker 全部已终止；
  - **连续 6 次**在满并发（16）运行中取消，线程不堆积、每次返回均为 0 残留；
  - 调用进入时已带中断信号（且 deadline 也已过期）→ 仍判定为取消而非超时；
  - worker 运行中超时 → 判定为超时、返回瞬间线程全灭；
  - 取消发生时仍有大量任务排队（reps ≫ workers）→ 排队任务同样被清理；
  - 串行路径（`parallelism=1`）取消的分类与整体失败；
  - 连续取消后再发正常批次：成功、与串行结果 0 容差一致、且无 worker 残留。
- 手工方式：启动服务后对 `POST /api/simulation/batch` 发一个重负载请求
  （大人群、大 `horizonHours`、较多 `replications`），在返回前中断客户端（如关闭连接 /
  Ctrl-C 掉请求）；随后用 `jstack <pid>` 观察，应看不到任何存活的 `batch-worker-*` 线程。

自动化测试覆盖：

- 容量拦截（`CapacityGuardTest`、`CapacityRejectionIntegrationTest`、
  `CapacityPropertiesOverrideTest`）：三个入口、六个资源维度逐维触发、边界含等号、
  首个超限维度稳定、**执行前拒绝（校准 `evaluationsUsed=0`）且不产生任何后台 worker**、
  以及通过 Spring 属性/环境变量外部覆盖上限。
- 带噪声校准（`CalibratorTest`）：噪声下区间不退化且宽度随噪声增大、区间包住估计值、
  `noiseSigma` 反映噪声、同请求串行/并行(1/8)/重复逐位一致、平坦观测判为
  `PARAMETER_UNIDENTIFIABLE`、容量拒绝带 `detailReason`。
- HTTP 端到端（`ApiEndToEndTest`）：真实 Spring 上下文走全部路径并断言状态码与结论。
- 取消/超时收尾（`BatchCancellationTest`、`BatchRunnerTest`）：返回瞬间本批 worker
  线程全部终止、连续多次取消不堆积、取消与超时不互相误报（含进入时已中断）、排队任务
  一并清理、串行/并行语义一致、取消后新批次正常且与串行结果逐位一致。
- 既有行为无回归：干预窗口边界、非法/冲突配置、容量逐维拒绝、同种子串并行逐次一致、
  校准失败分类与可复现性。
