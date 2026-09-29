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

### 取消 / 超时：全有或全无，且「返回即代表已全部停下」

批量重复在并行执行到一半被取消（例如上层超时或关闭连接、中断了本次调用的执行
线程）时，收尾语义是严格的：

- **整体失败**：取消或超时都只会抛出 `BatchCancelledException`，绝不返回部分结果。
  异常上的 `isTimeout()` 稳定区分两种语义——中断（包括调用进入时就已带有的中断信号）
  永远报「取消」（`isTimeout()==false`），deadline 到期且无中断永远报「超时」
  （`isTimeout()==true`）；两者同时出现时取消优先，不会互相误报。
- **返回即收尾完成**：抛出异常之前，所有在跑的任务都会被中断、线程池被关闭，
  并且 `run` 会**阻塞到属于本批次的每一个 worker 线程真正终止**（不仅是
  `awaitTermination` 完成，还逐个 `join` 到 `Thread.isAlive()==false`）。
  因此取消/超时返回的那一刻，已经没有任何后台计算在跑、没有任何 `batch-worker-*`
  线程存活；连续多次取消也不会堆积线程。
- **等待收尾时对调用方自身的中断不敏感**：收尾阶段会临时清除调用线程的中断位再等待，
  避免「等待被自身中断立即打断 → worker 还活着就返回」的泄漏；等待结束后再按语义恢复。
  取消会保留调用线程的中断状态；超时则**不会**设置中断位（超时不是中断，避免污染同一
  线程上紧接着的下一次正常调用）。
- **串并行一致**：串行（`parallelism=1`）与并行路径遵守同一套判定顺序（先看中断、再看
  deadline）与全有或全无语义。每个批次使用独立线程池，线程名带批次编号
  （`batch-worker-<batchId>`）；取消之后立即发起新的正常批量请求，不会受上一次残留
  状态影响，也不会串结果。

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
mvn test                 # 全部自动化测试（78 个）
mvn spring-boot:run      # 启动服务（默认 8080）

# 另一个终端：端到端 HTTP 走查（正常推演 / 批量串并行一致 / 带噪声校准 /
# 三类容量拒绝 / 非法请求 400），应输出 “RESULT pass=14 fail=0”
bash scripts/e2e.sh
# 端口不是 8080 时：E2E_BASE=http://localhost:8081 bash scripts/e2e.sh

# 验证外部容量覆盖（不改代码，把重复数上限调成 3）
APP_CAPACITY_MAX_REPLICATIONS=3 mvn spring-boot:run
```

### 复现并验证「取消返回瞬间后台线程已全部结束」

取消收尾由两个测试类负责，建议单独、重复运行以确认稳定（它们不依赖固定 sleep，
中断时机用「worker 已确实开始运行」/「调用线程已进入 run()」同步）：

```bash
# 确定性复现（注入一个「收到中断后仍有 250ms 收尾延迟」的可控重复任务）：
# 并行(8)取消返回瞬间断言 0 个 worker 存活、0 个任务在跑；
# 连续 5 次取消无堆积；进入时已带中断仍判定为取消而非超时；
# 超时不会污染调用线程中断位；串/并行下中断都优先于超时。
mvn test -Dtest=BatchCancellationCleanupTest

# 真实引擎下的严格断言（零宽限，在 run() 抛出的同一刻直接枚举线程）：
# 并行取消返回瞬间 batch-worker 立即为 0；连续 3 次重负载取消后再发正常批次成功；
# 串行取消判定为取消；既有超时/取消/串并行一致用例不回归。
mvn test -Dtest=BatchRunnerTest

# 多轮重复，确认无 flaky、无线程短暂堆积：
for i in $(seq 1 10); do mvn test -Dtest=BatchRunnerTest,BatchCancellationCleanupTest; done
```

判定要点：上述用例在「返回的那一刻」就枚举线程（不带任何等待宽限），因此一旦修复
不彻底（取消返回时 worker 仍活着）会立刻失败；真实引擎每个模拟小时检查一次中断，
属于协作式取消，收尾延迟以一个模拟步长为上界。

自动化测试覆盖：

- 取消/超时收尾（`BatchCancellationCleanupTest`、`BatchRunnerTest`）：返回瞬间无 worker
  存活、无任务在跑；连续多次取消不堆积不串状态；取消与超时语义稳定且互不误报（含进入
  时已带中断、超时与中断同时发生）；超时不污染调用线程中断位；串/并行行为一致；取消后
  新批次正常成功。
- 容量拦截（`CapacityGuardTest`、`CapacityRejectionIntegrationTest`、
  `CapacityPropertiesOverrideTest`）：三个入口、六个资源维度逐维触发、边界含等号、
  首个超限维度稳定、**执行前拒绝（校准 `evaluationsUsed=0`）且不产生任何后台 worker**、
  以及通过 Spring 属性/环境变量外部覆盖上限。
- 带噪声校准（`CalibratorTest`）：噪声下区间不退化且宽度随噪声增大、区间包住估计值、
  `noiseSigma` 反映噪声、同请求串行/并行(1/8)/重复逐位一致、平坦观测判为
  `PARAMETER_UNIDENTIFIABLE`、容量拒绝带 `detailReason`。
- HTTP 端到端（`ApiEndToEndTest`）：真实 Spring 上下文走全部路径并断言状态码与结论。
- 既有行为无回归：干预窗口边界、非法/冲突配置、取消/超时全有或全无（无线程泄漏）、
  同种子串并行逐次一致、校准失败分类与可复现性。
