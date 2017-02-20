package resa.optimize;

import backtype.storm.Config;
import backtype.storm.generated.StormTopology;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import resa.util.ConfigUtil;

import java.util.HashMap;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Created by ding on 14-4-30.
 * Modified by Tom Fu on Feb-10-2016
 */
public class SimpleGeneralAllocCalculator extends AllocCalculator {
    private static final Logger LOG = LoggerFactory.getLogger(SimpleGeneralAllocCalculator.class);
    private HistoricalCollectedData spoutHistoricalData;
    private HistoricalCollectedData boltHistoricalData;
    private int historySize;
    private int currHistoryCursor;

    @Override
    public void init(Map<String, Object> conf, Map<String, Integer> currAllocation, StormTopology rawTopology) {
        super.init(conf, currAllocation, rawTopology);
        historySize = ConfigUtil.getInt(conf, "resa.opt.win.history.size", 1);
        currHistoryCursor = ConfigUtil.getInt(conf, "resa.opt.win.history.size.ignore", 0);
        spoutHistoricalData = new HistoricalCollectedData(rawTopology, historySize);
        boltHistoricalData = new HistoricalCollectedData(rawTopology, historySize);
    }

    @Override
    public AllocResult calc(Map<String, AggResult[]> executorAggResults, int maxAvailableExecutors) {
        executorAggResults.entrySet().stream().filter(e -> rawTopology.get_spouts().containsKey(e.getKey()))
                .forEach(e -> spoutHistoricalData.putResult(e.getKey(), e.getValue()));
        executorAggResults.entrySet().stream().filter(e -> rawTopology.get_bolts().containsKey(e.getKey()))
                .forEach(e -> boltHistoricalData.putResult(e.getKey(), e.getValue()));
        // check history size. Ensure we have enough history data before we run the optimize function
        currHistoryCursor++;
        if (currHistoryCursor < historySize) {
            LOG.info("currHistoryCursor < historySize, curr: " + currHistoryCursor + ", Size: " + historySize
                    + ", DataHistorySize: "
                    + spoutHistoricalData.compHistoryResults.entrySet().stream().findFirst().get().getValue().size());
            return null;
        } else {
            currHistoryCursor = historySize;
        }

        ///TODO: Here we assume only one spout, how to extend to multiple spouts?
        ///TODO: here we assume only one running topology, how to extend to multiple running topologies?
        double targetQoSMs = ConfigUtil.getDouble(conf, "resa.opt.smd.qos.ms", 5000.0);
        int maxSendQSize = ConfigUtil.getInt(conf, Config.TOPOLOGY_EXECUTOR_SEND_BUFFER_SIZE, 1024);
        int maxRecvQSize = ConfigUtil.getInt(conf, Config.TOPOLOGY_EXECUTOR_RECEIVE_BUFFER_SIZE, 1024);
        double sendQSizeThresh = ConfigUtil.getDouble(conf, "resa.opt.smd.sq.thresh", 5.0);
        double recvQSizeThreshRatio = ConfigUtil.getDouble(conf, "resa.opt.smd.rq.thresh.ratio", 0.6);
        double recvQSizeThresh = recvQSizeThreshRatio * maxRecvQSize;

        double componentSampelRate = ConfigUtil.getDouble(conf, "resa.comp.sample.rate", 1.0);

//        Map<String, Map<String, Object>> queueMetric = new HashMap<>();
        Map<String, SourceNode> spInfos = spoutHistoricalData.compHistoryResults.entrySet().stream()
                .collect(Collectors.toMap(Map.Entry::getKey, e -> {
                    SpoutAggResult hisCar = AggResult.getHorizontalCombinedResult(new SpoutAggResult(), e.getValue());
                    int numberExecutor = currAllocation.get(e.getKey());
                    double avgSendQLenHis = hisCar.getSendQueueResult().getAvgQueueLength();
                    double avgRecvQLenHis = hisCar.getRecvQueueResult().getAvgQueueLength();

                    ///TODO: there we multiply 1/2 for this particular implementation
                    ///TODO: this shall be adjusted and configurable for ackering mechanism
                    double departRateHis = hisCar.getDepartureRatePerSec();
                    double tupleEmitRate = departRateHis * numberExecutor / 2.0;
                    double arrivalRateHis = hisCar.getArrivalRatePerSec();
                    double externalTupleArrivalRate = arrivalRateHis * numberExecutor;
                    double tupleEmitRateByInterArrival = hisCar.getSendQueueResult().getAvgArrivalRatePerSecond()* numberExecutor;
                    double tupleEmitInterArrivalScv = hisCar.getSendQueueResult().getScvInterArrivalTimes();
                    double externalRateByInterArrival = hisCar.getRecvQueueResult().getAvgArrivalRatePerSecond()* numberExecutor;
                    double externalTupleInterArrivalScv = hisCar.getRecvQueueResult().getScvInterArrivalTimes();

                    double avgCompleteLatencyHis = hisCar.getCombinedCompletedLatency().getAvg();///unit is millisecond

                    long totalCompleteTupleCnt = hisCar.getCombinedCompletedLatency().getCount();
                    double totalDurationSecond  = hisCar.getDurationSeconds();
                    double tupleCompleteRate = totalCompleteTupleCnt * numberExecutor / (totalDurationSecond * componentSampelRate);

                    LOG.info(String.format("(ID, eNum):(%s,%d), FinCnt: %d, Dur: %.1f, hSize: %d, sample: %.1f, FinRate: %.3f",
                            e.getKey(), numberExecutor, totalCompleteTupleCnt, totalDurationSecond, e.getValue().size(), componentSampelRate, tupleCompleteRate));
                    LOG.info(String.format("SQLen: %.1f, RQLen: %.1f, arrRateRQ: %.3f, arrRateSQ: %.3f",
                            avgSendQLenHis, avgRecvQLenHis, arrivalRateHis, departRateHis));
                    LOG.info(String.format("avgCTime: %.3f, EmitRate: %.3f, eArrRate: %.3f",
                            avgCompleteLatencyHis, tupleEmitRate, externalTupleArrivalRate));
                    LOG.info(String.format("EmitRateBIA: %.3f, EmitScv: %.3f, eArrRateBIA: %.3f, exArrScv: %.3f",
                            tupleEmitRateByInterArrival, tupleEmitInterArrivalScv, externalRateByInterArrival, externalTupleInterArrivalScv));

                    return new SourceNode(avgCompleteLatencyHis, totalCompleteTupleCnt, hisCar.getDurationMilliSeconds(), tupleEmitRate);
                }));

        SourceNode spInfo = spInfos.entrySet().stream().findFirst().get().getValue();

        Map<String, ServiceNode> queueingNetwork = boltHistoricalData.compHistoryResults.entrySet().stream()
                .collect(Collectors.toMap(Map.Entry::getKey, e -> {
                    BoltAggResult hisCar = AggResult.getHorizontalCombinedResult(new BoltAggResult(), e.getValue());
                    int numberExecutor = currAllocation.get(e.getKey());

                    double avgSendQLenHis = hisCar.getSendQueueResult().getAvgQueueLength();
                    double avgRecvQLenHis = hisCar.getRecvQueueResult().getAvgQueueLength();
                    double arrivalRateHis = hisCar.getArrivalRatePerSec();
                    double arrivalByInterArrival = hisCar.getRecvQueueResult().getAvgArrivalRatePerSecond() * numberExecutor;
                    double interArrivalScv = hisCar.getRecvQueueResult().getScvInterArrivalTimes();
                    double avgServTimeHis = hisCar.getCombinedProcessedResult().getAvg();///unit is millisecond
                    double avgServTimeScv = hisCar.getCombinedProcessedResult().getScv();

                    long totalProcessTupleCnt = hisCar.getCombinedProcessedResult().getCount();
                    double totalDurationSecond = hisCar.getDurationSeconds();
                    double tupleProcessRate = totalProcessTupleCnt * numberExecutor / (totalDurationSecond * componentSampelRate);

                    double lambdaHis = arrivalRateHis * numberExecutor;
                    double muHis = 1000.0 / avgServTimeHis;
                    //TODO: when processed tuple count is very small (e.g. there is no input tuple, avgServTimeHis outputs zero),
                    // avgServTime becomes zero and mu becomes infinity, this will cause problematic SN.
                    double rhoHis = lambdaHis / muHis;

                    boolean sendQLenNormalHis = avgSendQLenHis < sendQSizeThresh;
                    boolean recvQlenNormalHis = avgRecvQLenHis < recvQSizeThresh;

                    ///TODO: here i2oRatio can be INFINITY, when there is no data sent from Spout.
                    ///TODO: here we shall deside whether to use external Arrival rate, or tupleLeaveRateOnSQ!!
                    ///TODO: major differences 1) when there is max-pending control, tupleLeaveRateOnSQ becomes the
                    ///TODO: the tupleEmit Rate, rather than the external tuple arrival rate (implicit load shading)
                    ///TODO: if use tupleLeaveRateOnSQ(), be careful to check if ACKing mechanism is on, i.e.,
                    ///TODO: there are ack tuples. othersize, devided by tow becomes meaningless.
                    ///TODO: shall we put this i2oRatio calculation here, or later to inside ServiceModel?
                    double i2oRatio = lambdaHis / spInfo.getTupleLeaveRateOnSQ();

                    LOG.info(String.format("(ID, eNum):(%s,%d), ProcCnt: %d, Dur: %.1f, hSize: %d, sample: %.1f, ProcRate: %.3f",
                            e.getKey(), numberExecutor, totalProcessTupleCnt, totalDurationSecond, e.getValue().size(), componentSampelRate, tupleProcessRate));
                    LOG.info(String.format("SQLen: %.1f, RQLen: %.1f, arrRate: %.3f, arrRateScv: %.3f, avgSTime(ms): %.3f, avgSTimeScv: %.3f",
                            avgSendQLenHis, avgRecvQLenHis, arrivalRateHis, interArrivalScv, avgServTimeHis, avgServTimeScv));
                    LOG.info(String.format("rho: %.3f, lambda: %.3f, lambdaBIA: %.3f, mu: %.3f, ratio: %.3f",
                            rhoHis, lambdaHis, arrivalByInterArrival, muHis, i2oRatio));

                    return new ServiceNode(lambdaHis, muHis, ServiceNode.ServiceType.EXPONENTIAL, i2oRatio);
                }));
        int maxThreadAvailable4Bolt = maxAvailableExecutors - currAllocation.entrySet().stream()
                .filter(e -> rawTopology.get_spouts().containsKey(e.getKey()))
                .mapToInt(Map.Entry::getValue).sum();
        Map<String, Integer> boltAllocation = currAllocation.entrySet().stream()
                .filter(e -> rawTopology.get_bolts().containsKey(e.getKey()))
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
        AllocResult allocResult = SimpleGeneralServiceModel.checkOptimized(queueingNetwork,
                spInfo.getRealLatencyMilliSecond(), targetQoSMs, boltAllocation, maxThreadAvailable4Bolt);
        Map<String, Integer> retCurrAllocation = new HashMap<>(currAllocation);
        // merge the optimized decision into source allocation
        //The class AllocResult is updated on Feb 18, 2016 by Tom Fu, the adjustments are accordingly.
        retCurrAllocation.putAll(allocResult.kMaxOptAllocation);
        LOG.info(currAllocation + "-->" + retCurrAllocation);
        LOG.info("minReq: " + allocResult.minReqOptAllocation + ", status: " + allocResult.status);
        Map<String, Integer> retMinReqAllocation = null;
        if (allocResult.minReqOptAllocation != null) {
            retMinReqAllocation = new HashMap<>(currAllocation);
            // merge the optimized decision into source allocation
            retMinReqAllocation.putAll(allocResult.minReqOptAllocation);
        }
        Map<String, Object> ctx = new HashMap<>();
        ctx.put("latency", allocResult.getContext());
        ctx.put("spout", spInfo);
        ctx.put("bolt", queueingNetwork);
        return new AllocResult(allocResult.status, retMinReqAllocation, retCurrAllocation)
                .setContext(ctx);
    }

    @Override
    public void allocationChanged(Map<String, Integer> newAllocation) {
        super.allocationChanged(newAllocation);
        spoutHistoricalData.clear();
        boltHistoricalData.clear();
        currHistoryCursor = ConfigUtil.getInt(conf, "resa.opt.win.history.size.ignore", 0);
    }
}
