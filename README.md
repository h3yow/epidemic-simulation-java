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
- **取消 / 超时**：整体生效。所有任务被中断、线程池关闭并等待终止后抛出
  `BatchCancelledException`（`isTimeout()` 区分超时与显式取消）；绝不返回部分结果，
  不留后台运行的任务（测试断言工作线程数归零）。

## 校准失败原因分类

校准结果永不返回空结果或伪装成功；失败时 `failureReason` 取以下之一：

| 原因 | 含义 |
|---|---|
| `PARAMETER_OUT_OF_BOUNDS` | 参数边界非法（如 `0 < betaLower < betaUpper` 不成立）或基准情景非法 |
| `INSUFFICIENT_DATA` | 观测点不足（< 5）、含非法值或长度超过情景时域 |
| `NON_CONVERGENCE` | 评估预算耗尽仍未收敛，或最优点处 SSE 曲率非正（参数不可识别） |
| `RESOURCE_LIMIT_EXCEEDED` | 请求超出资源上限（replications ≤ 1000、evaluations ≤ 500、总模拟小时数 ≤ 2×10⁷），**在执行前拒绝** |

校准方法：网格粗搜 + 黄金分割细化，最小化观测曲线与均值模拟曲线的 SSE；
不确定度由最优点处 SSE 曲率（正态近似）给出 95% 置信区间。
每次目标评估使用同一 `masterSeed`，因此整个校准对同一请求完全可复现。

## REST API

- `POST /api/simulation/batch` — 批量随机重复实验，返回聚合结果；
  非法配置 → 400（含 `reason`），取消/超时 → 408。
- `POST /api/calibration/beta` — 参数校准；失败 → 422（含 `failureReason`）。

## 本地验证

```bash
mvn test          # 全部单元测试（26 个）
mvn spring-boot:run   # 本地启动服务（默认 8080 端口）
```

测试覆盖：干预窗口边界（`activeAt`、边界小时的速率取值）、非法与冲突配置
（按 `Reason` 区分断言）、取消/超时后的状态（异常类型、`isTimeout`、无线程泄漏、
批次可继续复用）、同一种子在串行与并行（1/4/8 线程）下的逐次一致性、
校准失败原因分类与同一请求的可复现性。
