# Epidemic Scenario Simulation & Parameter Calibration

一个可在本地完整运行的传染病情景推演、批量重复与参数校准服务（Spring Boot, Java 21）。
同一请求在串行与并行执行下给出**可复现、可解释且一致**的结论；所有吃算力的入口
在**真正开始计算之前**按同一套外部可配置上限校验；带噪声观测下的校准区间
**不退化为一个点**，并可区分“撑不起区间 / 参数不可识别”等失败原因。

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
- 非法参数一律抛出 `InvalidScenarioException` 并携带机器可区分的 `Reason`。

## 统一容量上限（执行前拦截）

三个真正吃算力的入口——**单次推演 `POST /api/simulation/single`、
批量重复 `POST /api/simulation/batch`、参数校准 `POST /api/calibration/beta`**——
都在任何模拟/线程/目标评估发生之前，经过同一个 `CapacityGuard` 校验。
超限立即拒绝：不会跑一半、不返回空结果冒充成功，也不会在后台继续跑任务。

### 受管维度与机器可区分原因

| 维度 | 配置键 | 默认上限 | 拒绝原因码（`reason` / `capacityReason`） |
|---|---|---|---|
| 人群规模 N | `epidemic.capacity.max-population` | 10 000 000 | `POPULATION_TOO_LARGE` |
| 单场景时域（小时） | `epidemic.capacity.max-horizon-hours` | 100 000 | `HORIZON_TOO_LONG` |
| 随机重复次数 | `epidemic.capacity.max-replications` | 1 000 | `TOO_MANY_REPLICATIONS` |
| 并行度（线程数） | `epidemic.capacity.max-parallelism` | 16 | `PARALLELISM_TOO_HIGH` |
| 校准目标评估次数 | `epidemic.capacity.max-evaluations` | 500 | `TOO_MANY_EVALUATIONS` |
| 观测点数量 | `epidemic.capacity.max-observations` | 100 000 | `TOO_MANY_OBSERVATIONS` |
| 聚合工作量（重复 × 评估 × 小时） | `epidemic.capacity.max-total-simulated-hours` | 20 000 000 | `TOTAL_WORK_TOO_LARGE` |

- 单次推演校验：人群、时域。
- 批量重复校验：人群、时域、重复次数、并行度、`重复 × 小时`。
- 参数校验：人群、时域、重复次数、并行度、评估次数、观测点数、`评估 × 重复 × 小时`。
- 上限为**闭区间**：等于上限允许，大于才拒绝。校验顺序固定（标量维度先于聚合工作量），
  因此同一请求被拒绝时报告的维度是确定的。
- 拒绝响应：
  - 单次/批量返回 **HTTP 422**：
    `{"error":"CAPACITY_EXCEEDED","reason":"<原因码>","requested":...,"limit":...,"message":...}`。
  - 校准返回 **HTTP 422**，结果体内 `failureReason="RESOURCE_LIMIT_EXCEEDED"`，
    且额外带 `capacityReason="<原因码>"` 标明是哪一维超限（`evaluationsUsed=0`，未做任何评估）。

### 本地如何配置 / 覆盖

所有上限都可通过标准 Spring Boot 属性源覆盖，无需改代码，任选其一：

```bash
# 1) 环境变量（部署/容器常用）
EPIDEMIC_CAPACITY_MAX_REPLICATIONS=4000 \
EPIDEMIC_CAPACITY_MAX_TOTAL_SIMULATED_HOURS=100000000 \
mvn spring-boot:run

# 2) JVM 系统属性
mvn spring-boot:run -Dspring-boot.run.jvmArguments="\
-Depidemic.capacity.max-replications=4000 \
-Depidemic.capacity.max-parallelism=32"

# 3) 直接写 src/main/resources/application.properties（键名见上表）
#    或用 --spring.config.additional-location 指向外部配置文件
```

## 可复现性约定（种子与并行度）

- 单次推演：请求可带 `seed`；不带则服务生成并在响应中回显，同种子结果完全一致。
- 批量请求携带 `masterSeed`；每个重复的种子在**任务调度之前**按索引顺序串行派生，
  与并行度、任务划分无关。聚合（均值、2.5%/97.5% 百分位，R type-7）按重复索引顺序进行。
- 校准的每次目标评估使用同一 `masterSeed`，目标函数是 beta 的确定函数，因此
  **点估计与不确定度区间对同一请求逐位一致，与并行度无关、重复执行不变**。
- 取消 / 超时整体生效：中断全部任务、线程池关闭并**确定性等待工作线程消亡**后抛出
  `BatchCancelledException`；绝不返回部分结果，不留后台任务。
- 日志打印关键输入输出：种子、并行度、请求规模（重复/时域/观测数/评估预算）、
  聚合值（峰值均值、百分位、总康复）以及校准的点估计、`CI95`、`sigma2`、蒙特卡洛噪声项等。

## 带噪声观测下的不确定度区间（含义与失败分类）

校准最小化“观测每小时感染数”与“均值模拟曲线”的 SSE（25 点网格粗搜 + 黄金分割细化）。
不确定度**不再**使用最优点处的有限差分曲率（对噪声敏感、易退化为点），改为
**轮廓似然（profile likelihood）区间**：

1. 在最优点 beta* 处估计逐点方差 `sigma2 = max(残差方差, 蒙特卡洛噪声地板)`；
   噪声地板 = 各 beta 下批量均值曲线的抽样方差（由各重复轨迹无偏估计后除以重复数）。
   因此即使观测几乎无噪声，区间也不会缩成一个点；真实观测噪声越大，区间如实变宽。
2. 95% 阈值 = `SSE(beta*) + sigma2 · χ²₀.₉₅(1)`（3.84146）；向最优两侧几何外扩 +
   二分求 SSE 越过阈值的 beta，得到 `[betaLower95, betaUpper95]`。
3. 区间完全由 `(请求, masterSeed)` 决定，所以**重复执行、更换并行度结论严格一致**。

校准结果永不返回空结果或伪装成功；失败时 `failureReason` 取以下之一（HTTP 422）：

| 原因 | 含义 |
|---|---|
| `PARAMETER_OUT_OF_BOUNDS` | 参数边界非法或基准情景非法 |
| `INSUFFICIENT_DATA` | 观测点不足（< 5）、含非法值或长度超过时域 |
| `NON_CONVERGENCE` | 评估预算耗尽，最优点都未稳定收敛 |
| `RESOURCE_LIMIT_EXCEEDED` | 超出容量上限，执行前拒绝；`capacityReason` 指明具体维度 |
| `PARAMETER_UNIDENTIFIABLE` | 数据撑不起区间：最优贴参数边界，或最优附近轮廓 SSE 的抬升只有蒙特卡洛噪声量级（如平坦无信息曲线） |
| `UNCERTAINTY_NOT_QUANTIFIABLE` | 点估计存在，但追踪区间时预算耗尽，或区间数值退化 |

## REST API

| 方法与路径 | 说明 | 失败状态 |
|---|---|---|
| `POST /api/simulation/single` | 单次随机推演（body: `{"scenario":..., "seed":...}`，seed 可省略） | 非法情景 400；超容量 422 |
| `POST /api/simulation/batch` | 批量随机重复，返回聚合结果 | 非法 400；超容量 422；取消/超时 408 |
| `POST /api/calibration/beta` | beta 参数校准，返回点估计与 95% 区间 | 422（含 `failureReason`，容量类另带 `capacityReason`） |

## 本地端到端验证

```bash
mvn test              # 全部自动化测试（46 个）
./scripts/e2e-smoke.sh   # 启动服务并用真实 HTTP 走完所有路径（27 条断言）
mvn spring-boot:run   # 手动启动（默认 8080）
```

冒烟脚本覆盖：正常单次推演（种子可复现）、批量串行与并行结论一致、
带噪声观测校准（区间非退化、覆盖真值、重复与并行度一致）、
三个入口各维度的容量拦截（422 + 可区分原因码）、平坦数据的不可识别失败。

自动化测试覆盖：容量各维度（单元 + 三入口集成，断言拒绝发生在执行前且无线程残留、
校准 `evaluationsUsed=0`）、带噪声观测的区间行为（非退化、噪声越大区间越宽、
重复/并行稳定、不可识别与预算耗尽的区分性失败）；
以及既有的干预窗口规则、取消/超时无线程泄漏、同种子跨并行度逐位一致等行为（无回归）。
