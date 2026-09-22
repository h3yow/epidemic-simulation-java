package com.github.h3yow.epidemicsimulationjava.calibration;

import com.github.h3yow.epidemicsimulationjava.batch.BatchRunner;
import com.github.h3yow.epidemicsimulationjava.config.SimulationProperties;
import com.github.h3yow.epidemicsimulationjava.engine.ScenarioValidator;
import com.github.h3yow.epidemicsimulationjava.exception.SimulationException;
import com.github.h3yow.epidemicsimulationjava.model.BatchRequest;
import com.github.h3yow.epidemicsimulationjava.model.BatchResult;
import com.github.h3yow.epidemicsimulationjava.model.CalibrationRequest;
import com.github.h3yow.epidemicsimulationjava.model.CalibrationResult;
import com.github.h3yow.epidemicsimulationjava.model.EpidemicScenario;
import com.github.h3yow.epidemicsimulationjava.model.FailureReason;
import com.github.h3yow.epidemicsimulationjava.model.GridPoint;
import com.github.h3yow.epidemicsimulationjava.model.Observation;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 基于观测数据的 beta 网格搜索校准。
 *
 * <p>对每个候选 beta 用固定种子批量重复实验得到均值曲线，与观测比较计算 RMSE；
 * 以高斯权重 w = exp(-rmse² / (2·bestRmse²)) 归一化后给出加权均值与 5%/95% 加权
 * 分位数区间作为不确定度。
 *
 * <p>失败原因可区分：参数越界/数据不足/资源超限/无法收敛分别返回
 * INVALID_CALIBRATION_REQUEST / INSUFFICIENT_DATA / RESOURCE_LIMIT_EXCEEDED /
 * NO_CONVERGENCE，绝不返回空结果或伪成功。
 */
@Service
public class CalibrationService {

    private static final Logger log = LoggerFactory.getLogger(CalibrationService.class);

    /** 校准批量实验的固定基础种子：同一请求重复校准结论一致。 */
    private static final long CALIBRATION_BASE_SEED = 0xC0FFEE_5EEDL;

    private final BatchRunner batchRunner;
    private final ScenarioValidator validator;
    private final SimulationProperties properties;

    public CalibrationService(BatchRunner batchRunner, ScenarioValidator validator,
                              SimulationProperties properties) {
        this.batchRunner = batchRunner;
        this.validator = validator;
        this.properties = properties;
    }

    public CalibrationResult calibrate(CalibrationRequest request) {
        validateRequest(request);
        validator.validate(request.scenario());

        List<GridPoint> grid = new ArrayList<>(request.gridPoints());
        double step = (request.betaHigh() - request.betaLow()) / (request.gridPoints() - 1);
        double bestRmse = Double.POSITIVE_INFINITY;
        int bestIdx = -1;

        for (int g = 0; g < request.gridPoints(); g++) {
            double beta = request.betaLow() + g * step;
            EpidemicScenario candidate = withBeta(request.scenario(), beta);
            BatchResult batch = batchRunner.run(new BatchRequest(
                    candidate, request.replicationsPerPoint(), CALIBRATION_BASE_SEED,
                    1, request.timeout()));
            double rmse = rmse(batch, request.observations());
            grid.add(new GridPoint(beta, rmse, 0.0));
            if (rmse < bestRmse) {
                bestRmse = rmse;
                bestIdx = g;
            }
        }

        if (!Double.isFinite(bestRmse) || bestRmse > request.rmseTolerance()) {
            throw new SimulationException(FailureReason.NO_CONVERGENCE,
                    "网格内最优 RMSE=" + bestRmse + " 超过收敛容差 " + request.rmseTolerance()
                            + "；请放宽容差或扩大 beta 搜索范围");
        }

        // 高斯权重 + 归一化
        double denom = 2.0 * bestRmse * bestRmse;
        double weightSum = 0.0;
        List<GridPoint> weighted = new ArrayList<>(grid.size());
        for (GridPoint gp : grid) {
            double w = denom > 0 ? Math.exp(-(gp.rmse() * gp.rmse()) / denom) : 1.0;
            weighted.add(new GridPoint(gp.beta(), gp.rmse(), w));
            weightSum += w;
        }
        if (weightSum <= 0.0 || !Double.isFinite(weightSum)) {
            throw new SimulationException(FailureReason.NO_CONVERGENCE, "权重归一化失败，无法给出不确定度");
        }

        double betaEstimate = 0.0;
        for (GridPoint gp : weighted) {
            betaEstimate += gp.beta() * gp.weight() / weightSum;
        }
        double low = weightedQuantile(weighted, weightSum, 0.05);
        double high = weightedQuantile(weighted, weightSum, 0.95);

        CalibrationResult result = new CalibrationResult(
                betaEstimate, low, high, bestRmse, weighted, request.replicationsPerPoint());
        log.info("calibration done: betaEstimate={} interval=[{},{}] bestRmse={} gridPoints={} replicationsPerPoint={}",
                betaEstimate, low, high, bestRmse, request.gridPoints(), request.replicationsPerPoint());
        return result;
    }

    private void validateRequest(CalibrationRequest r) {
        if (r == null) {
            throw new SimulationException(FailureReason.INVALID_CALIBRATION_REQUEST, "CalibrationRequest 不能为空");
        }
        if (r.observations() == null || r.observations().isEmpty()) {
            throw new SimulationException(FailureReason.INSUFFICIENT_DATA, "观测数据为空");
        }
        if (r.observations().size() < properties.minObservations()) {
            throw new SimulationException(FailureReason.INSUFFICIENT_DATA,
                    "观测点数量 " + r.observations().size() + " 少于最少要求 " + properties.minObservations());
        }
        int steps = r.scenario() == null ? 0 : r.scenario().steps();
        for (Observation obs : r.observations()) {
            if (obs == null || obs.step() < 0 || obs.step() >= steps || obs.observedInfected() < 0) {
                throw new SimulationException(FailureReason.INVALID_CALIBRATION_REQUEST,
                        "观测点非法（step 必须在 [0, steps) 且计数非负）: " + obs);
            }
        }
        if (!Double.isFinite(r.betaLow()) || !Double.isFinite(r.betaHigh())
                || r.betaLow() < 0.0 || r.betaHigh() > 1.0 || r.betaLow() >= r.betaHigh()) {
            throw new SimulationException(FailureReason.INVALID_CALIBRATION_REQUEST,
                    "beta 搜索区间非法: [" + r.betaLow() + ", " + r.betaHigh() + "]，必须满足 0 <= low < high <= 1");
        }
        if (r.gridPoints() < 2) {
            throw new SimulationException(FailureReason.INVALID_CALIBRATION_REQUEST,
                    "gridPoints 必须 >= 2，实际: " + r.gridPoints());
        }
        if (r.gridPoints() > properties.maxGridPoints()) {
            throw new SimulationException(FailureReason.RESOURCE_LIMIT_EXCEEDED,
                    "gridPoints=" + r.gridPoints() + " 超过上限 " + properties.maxGridPoints());
        }
        if (r.replicationsPerPoint() < 1) {
            throw new SimulationException(FailureReason.INVALID_CALIBRATION_REQUEST,
                    "replicationsPerPoint 必须 >= 1，实际: " + r.replicationsPerPoint());
        }
        long totalRuns = (long) r.gridPoints() * r.replicationsPerPoint();
        if (totalRuns > properties.maxReplications()) {
            throw new SimulationException(FailureReason.RESOURCE_LIMIT_EXCEEDED,
                    "校准总仿真次数 gridPoints*replicationsPerPoint=" + totalRuns
                            + " 超过上限 " + properties.maxReplications());
        }
        if (!Double.isFinite(r.rmseTolerance()) || r.rmseTolerance() <= 0.0) {
            throw new SimulationException(FailureReason.INVALID_CALIBRATION_REQUEST,
                    "rmseTolerance 必须为正有限值，实际: " + r.rmseTolerance());
        }
        if (r.timeout() == null || r.timeout().isNegative() || r.timeout().isZero()) {
            throw new SimulationException(FailureReason.INVALID_CALIBRATION_REQUEST, "timeout 必须为正时长");
        }
        if (r.timeout().compareTo(properties.maxTimeout()) > 0) {
            throw new SimulationException(FailureReason.RESOURCE_LIMIT_EXCEEDED,
                    "timeout=" + r.timeout() + " 超过上限 " + properties.maxTimeout());
        }
    }

    private static EpidemicScenario withBeta(EpidemicScenario s, double beta) {
        return new EpidemicScenario(s.name(), s.population(), s.initialExposed(),
                s.initialInfectious(), beta, s.sigmaPerStep(), s.gammaPerStep(),
                s.steps(), s.interventions());
    }

    private static double rmse(BatchResult batch, List<Observation> observations) {
        double sumSq = 0.0;
        for (Observation obs : observations) {
            double predicted = batch.trace().get(obs.step()).mean();
            double diff = predicted - obs.observedInfected();
            sumSq += diff * diff;
        }
        return Math.sqrt(sumSq / observations.size());
    }

    /** 按 beta 升序的加权分位数（权重已按 weightSum 归一）。 */
    private static double weightedQuantile(List<GridPoint> grid, double weightSum, double q) {
        List<GridPoint> sorted = grid.stream()
                .sorted(Comparator.comparingDouble(GridPoint::beta))
                .toList();
        double cumulative = 0.0;
        for (GridPoint gp : sorted) {
            cumulative += gp.weight() / weightSum;
            if (cumulative >= q) {
                return gp.beta();
            }
        }
        return sorted.get(sorted.size() - 1).beta();
    }
}
