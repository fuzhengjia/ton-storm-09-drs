package resa.optimize;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Created by Tom.fu on Feb-15-2016
 * Chain topology and not tuple split
 * TODO: the current implementation has adjustRatio, in future, we will design general regression model
 * TODO: or prediction method to replace this.
 * <p>
 * TODO: sn.getI2oRatio() can be INFINITY, i.e., special case, there is no input data
 * <p>
 * TODO: we have already tried in this version! when we calculate Var(X), where X are sampled results, Var(X) shall multiply n/(n-1), n is element counts of X.
 * The adjustment on Var(x) has been tested. The effect is too small (since n is quite large), therefore, we decide to ignore this.
 */
public class MMKServiceModel {

    private static final Logger LOG = LoggerFactory.getLogger(MMKServiceModel.class);

    public enum ServiceModelType {
        MMK(0), GGK(1);
        private static final int totalTypeCount = 2;
        private final int value;

        ServiceModelType(int value) {
            this.value = value;
        }

        public int getValue() {
            return value;
        }
    }

    /**
     * We assume the stability check for each node is done beforehand!
     * Jackson OQN assumes all the arrival and departure are iid and exponential
     * <p>
     * Note, the return time unit is in Second!
     *
     * @param serviceNodes, the service node configuration, in this function, chain topology is assumed.
     * @param allocation,   the target allocation to be analyzed
     * @return here we should assume all the components are stable, the stability check shall be done outside this function
     */
    public static double getExpectedTotalSojournTimeForJacksonOQN(Map<String, GeneralServiceNode> serviceNodes, Map<String, Integer> allocation) {

        if (allocation == null){
            return Double.POSITIVE_INFINITY;
        }

        double retVal = 0.0;
        for (Map.Entry<String, GeneralServiceNode> e : serviceNodes.entrySet()) {
            String cid = e.getKey();
            GeneralServiceNode serviceNode = e.getValue();
            int serverCount = allocation.get(cid).intValue();

            double avgSojournTime = sojournTime_MMK(serviceNode.getLambda(), serviceNode.getMu(), serverCount);
            retVal += (avgSojournTime * serviceNode.getRatio());
        }
        return retVal;
    }


    /**
     * @param serviceNodes
     * @param totalResourceCount
     * @return null if a) minReq of any component is Integer.MAX_VALUE (invalid parameter mu = 0.0)
     * b) total minReq can not be satisfied (total minReq > totalResourceCount)
     * otherwise, the Map data structure.
     */
    public static Map<String, Integer> suggestAllocationGeneralTopApplyMMK(Map<String, GeneralServiceNode> serviceNodes, int totalResourceCount) {
        Map<String, Integer> retVal = serviceNodes.entrySet().stream().collect(Collectors.toMap(Map.Entry::getKey,
                e -> getMinReqServerCount(e.getValue().getLambda(), e.getValue().getMu())));
        int topMinReq = retVal.values().stream().mapToInt(Integer::intValue).sum();

        LOG.debug("Apply M/M/K, resCnt: " + totalResourceCount + ", topMinReq: " + topMinReq);
        if (topMinReq <= totalResourceCount) {
            int remainCount = totalResourceCount - topMinReq;
            for (int i = 0; i < remainCount; i++) {
                double maxDiff = -1;
                String maxDiffCid = null;

                for (Map.Entry<String, GeneralServiceNode> e : serviceNodes.entrySet()) {
                    String cid = e.getKey();
                    GeneralServiceNode sn = e.getValue();
                    int currentAllocated = retVal.get(e.getKey());

                    double beforeAddT = sojournTime_MMK(sn.getLambda(), sn.getMu(), currentAllocated);
                    double afterAddT = sojournTime_MMK(sn.getLambda(), sn.getMu(), currentAllocated + 1);

                    double diff = (beforeAddT - afterAddT) * sn.getRatio();
                    if (diff > maxDiff) {
                        maxDiff = diff;
                        maxDiffCid = cid;
                    }
                }
                if (maxDiffCid != null) {
                    int newAllocate = retVal.compute(maxDiffCid, (k, count) -> count + 1);
                    LOG.debug((i + 1) + " of " + remainCount + ", assigned to " + maxDiffCid + ", newAllocate: " + newAllocate);
                } else {
                    LOG.debug("Null MaxDiffCid returned in " + (i + 1) + " of " + remainCount);
                    for (Map.Entry<String, GeneralServiceNode> e : serviceNodes.entrySet()) {
                        String cid = e.getKey();
                        GeneralServiceNode sn = e.getValue();
                        int currentAllocated = retVal.get(cid);

                        double beforeAddT = sojournTime_MMK(sn.getLambda(), sn.getMu(), currentAllocated);
                        double afterAddT = sojournTime_MMK(sn.getLambda(), sn.getMu(), currentAllocated + 1);

                        LOG.debug(cid + ", currentAllocated: " + currentAllocated
                                + ", beforeAddT: " + beforeAddT
                                + ", afterAddT: " + afterAddT);
                    }
                    return retVal;
                }
            }
        } else {
            LOG.info(String.format("topMinReq (%d) > totalResourceCount (%d)", topMinReq, totalResourceCount));
            return null;
        }
        return retVal;
    }

    /**
     * Like Module A', input required QoS, output #threads required
     * Here we separate to two modules: first output allocation, then calculate total #threads included.
     * Caution all the computation involved is in second unit.
     *
     *
     * @param realLatencyMilliSeconds
     * @param estTotalSojournTimeMilliSec_MMK
     * @param serviceNodes
     * @param completeTimeMilliSecUpper
     * @param completeTimeMilliSecLower
     * @param currentUsedThreadByBolts
     * @param maxAvailableExec
     * @return null when status is INFEASIBLE; or FEASIBLE reallocation (with resource added)
     */
    public static Map<String, Integer> getMinReqServerAllocationGeneralTopApplyMMK(
            double realLatencyMilliSeconds, double estTotalSojournTimeMilliSec_MMK, Map<String, GeneralServiceNode> serviceNodes,
            double completeTimeMilliSecUpper, double completeTimeMilliSecLower, int currentUsedThreadByBolts, int maxAvailableExec, int reUnit) {

        double lowerBoundServiceTimeSeconds = 0.0;  //in seconds
        int totalMinReq = 0;
        for (Map.Entry<String, GeneralServiceNode> e : serviceNodes.entrySet()) {
            double lambda = e.getValue().getLambda();
            double mu = e.getValue().getMu();
            ///caution, the unit should be millisecond
            lowerBoundServiceTimeSeconds += (1.0 / mu);
            totalMinReq += getMinReqServerCount(lambda, mu);
        }

        double adjRatio = Math.max(1.0, realLatencyMilliSeconds / estTotalSojournTimeMilliSec_MMK);

        Map<String, Integer> currAllocation = null;
        if (lowerBoundServiceTimeSeconds * adjRatio * 1000.0 < completeTimeMilliSecUpper && totalMinReq < maxAvailableExec) {

            LOG.debug("In getMinReqServerAllocationGeneralTopApplyMMK(), " +
                    "lowerBoundServiceTimeSeconds * adjRatio * 1000.0 < completeTimeMilliSecUpper && totalMinReq < maxAvailableExec");
            int i = currentUsedThreadByBolts + reUnit;
            for (; i <= maxAvailableExec; i += reUnit) {
                currAllocation = suggestAllocationGeneralTopApplyMMK(serviceNodes, i);
                double currTime = getExpectedTotalSojournTimeForJacksonOQN(serviceNodes, currAllocation);

                LOG.debug(String.format("completeT upper bound (ms): %.4f, rawCompleteTime(ms): %.4f, afterAdjust(ms): %.4f, totalMinReqQoS: %d",
                        completeTimeMilliSecUpper, currTime * 1000.0, currTime * 1000.0 * adjRatio, i));
                if (currTime * 1000.0 * adjRatio < completeTimeMilliSecUpper) {
                    break;
                }
            }

            if (i <= maxAvailableExec) {
                return currAllocation;
            }
        }
        return null;
    }

    public static Map<String, Integer> getRemovedAllocationGeneralTopApplyMMK(
            double realLatencyMilliSeconds, double estTotalSojournTimeMilliSec_MMK, Map<String, GeneralServiceNode> serviceNodes,
            double completeTimeMilliSecUpper, double completeTimeMilliSecLower, int currentUsedThreadByBolts, int reUnit) {

        int totalMinReq2 = 0;
        for (Map.Entry<String, GeneralServiceNode> e : serviceNodes.entrySet()) {
            double lambda = e.getValue().getLambda();
            double mu = e.getValue().getMu();
            totalMinReq2 += getMinReqServerCount(lambda, mu);
        }

        Map<String, Integer> minPossibleAllocation = serviceNodes.entrySet().stream().collect(Collectors.toMap(Map.Entry::getKey,
                e -> getMinReqServerCount(e.getValue().getLambda(), e.getValue().getMu())));
        int totalMinReq = minPossibleAllocation.values().stream().mapToInt(Integer::intValue).sum();

        if (totalMinReq != totalMinReq2){
            LOG.warn("in getMinReqServerAllocationGeneralTopApplyMMK(), totalMinReq (" + totalMinReq + ") != totalMinReq2 (" + totalMinReq2 + ").");
        }

        double adjRatio = realLatencyMilliSeconds / estTotalSojournTimeMilliSec_MMK;

        Map<String, Integer> currAllocation = null;
        if (currentUsedThreadByBolts > totalMinReq){
            LOG.debug("In getRemovedAllocationGeneralTopApplyMMK(), currentUsedThreadByBolts > totalMinReq");
            int i = currentUsedThreadByBolts - reUnit;
            for (; i > totalMinReq; i = i - reUnit) {
                currAllocation = suggestAllocationGeneralTopApplyMMK(serviceNodes, i);
                double currTime = getExpectedTotalSojournTimeForJacksonOQN(serviceNodes, currAllocation);

                LOG.debug(String.format("completeT lower bound (ms): %.4f, rawCompleteTime(ms): %.4f, afterAdjust(ms): %.4f, totalMinReqQoS: %d",
                        completeTimeMilliSecLower, currTime * 1000.0, currTime * 1000.0 * adjRatio, i));
                if (currTime * 1000.0 * adjRatio > completeTimeMilliSecLower) {
                    break;
                }
            }
            if (i > totalMinReq) {
                return currAllocation;
            }
        }

        return minPossibleAllocation;
    }

    /**
     * Created by Tom Fu on Feb 21, 2017, for ToN Major revision-1, to enable resource adjustment and auto-reallocation
     * Three cases for consideration:
     * 1) resource over-provision, i.e., too much resources are used, and the real latency is far below the allowed bound
     * 2) resource shortage, i.e., resource is not enough , hence the real latency is beyond the upper-bound
     * 3) resource proper, case a) resource is just fine, only need to check whether it is in good allocation.
     * case b) the current allocation is bad, however, after reallocation to optimal allocation,
     * it will be below upper-bound
     *
     * @param realLatencyMilliSeconds
     * @param estTotalSojournTimeMilliSec_MMK
     * @param serviceNodes
     * @param completeTimeMilliSecUpper
     * @param completeTimeMilliSecLower,
     * @return AllocResult.Status
     */
    public static AllocResult.Status getStatusMMK(
            double realLatencyMilliSeconds, double estTotalSojournTimeMilliSec_MMK, double estTotalSojournTimeMilliSec_MMKOpt,
            Map<String, GeneralServiceNode> serviceNodes, double completeTimeMilliSecUpper, double completeTimeMilliSecLower) {

        double ratio = Math.max(1.0, realLatencyMilliSeconds / estTotalSojournTimeMilliSec_MMK);
        if (realLatencyMilliSeconds < completeTimeMilliSecLower) {
            return AllocResult.Status.OVERPROVISIONING;
        } else if (realLatencyMilliSeconds > completeTimeMilliSecUpper
                && ratio * estTotalSojournTimeMilliSec_MMKOpt > completeTimeMilliSecUpper) {

            //TODO: Here we conservatively include the case that the when "realLatencyMilliSeconds > completeTimeMilliSecUpper",
            //TODO: but current allocation is not the optimal one, then we will consider try optimal one before add more resources.
            return AllocResult.Status.SHORTAGE;
        }
        return AllocResult.Status.FEASIBLE;
    }

    /**
     * @param sourceNode
     * @param queueingNetwork
     * @param completeTimeMilliSecUpper
     * @param completeTimeMilliSecLower
     * @param currBoltAllocation
     * @param maxAvailable4Bolt best effort resource utilization!
     * @param currentUsedThreadByBolts
     * @return status indicates:
     *              a) INFEASIBLE       best effort resource utilization is not enough -> system overloaded, may not be stable or load shedding
     *              b) SHORTAGE         provide solution for adding more executors
     *              c) OVERPROVISION    provide solution for removing executors
     *              d) FEASIBLE         existing resources are just fine, not adding or removing needed,
     *                                  but probably need do re-scheduling when non-optimal allocation is detected.
     */
    public static AllocResult checkOptimized_MMK(
            GeneralSourceNode sourceNode, Map<String, GeneralServiceNode> queueingNetwork,
            double completeTimeMilliSecUpper, double completeTimeMilliSecLower,
            Map<String, Integer> currBoltAllocation, int maxAvailable4Bolt, int currentUsedThreadByBolts, int resourceUnit) {

        /// Caution about the time unit!, second is used in all the functions of calculation
        /// millisecond is used in the output display!
        Map<String, Integer> kMaxOptAllocation = suggestAllocationGeneralTopApplyMMK(queueingNetwork, maxAvailable4Bolt);
        Map<String, Integer> currOptAllocation = suggestAllocationGeneralTopApplyMMK(queueingNetwork, currentUsedThreadByBolts);
        double estTotalSojournTimeMilliSec_MMKOpt = 1000.0 * getExpectedTotalSojournTimeForJacksonOQN(queueingNetwork, currOptAllocation);
        double estTotalSojournTimeMilliSec_MMK = 1000.0 * getExpectedTotalSojournTimeForJacksonOQN(queueingNetwork, currBoltAllocation);

        double realLatencyMilliSeconds = sourceNode.getRealLatencyMilliSeconds();
        ///for better estimation, we remain (learn) this ratio, and assume that the estimated is always smaller than real.
        double underEstimateRatio = Math.max(1.0, realLatencyMilliSeconds / estTotalSojournTimeMilliSec_MMK);
        ///relativeError (rE)
        double relativeError = Math.abs(realLatencyMilliSeconds - estTotalSojournTimeMilliSec_MMK) * 100.0 / realLatencyMilliSeconds;

        AllocResult.Status status = getStatusMMK(realLatencyMilliSeconds, estTotalSojournTimeMilliSec_MMK, estTotalSojournTimeMilliSec_MMKOpt,
                queueingNetwork, completeTimeMilliSecUpper, completeTimeMilliSecLower);

        Map<String, Integer> adjustedAllocation = null;
        if (status.equals(AllocResult.Status.SHORTAGE)) {
            LOG.debug("Status is resource shortage, calling resource adjustment");
            adjustedAllocation = getMinReqServerAllocationGeneralTopApplyMMK(
                    realLatencyMilliSeconds, estTotalSojournTimeMilliSec_MMK, queueingNetwork,
                    completeTimeMilliSecUpper, completeTimeMilliSecLower, currentUsedThreadByBolts, maxAvailable4Bolt, resourceUnit);

            if (adjustedAllocation == null){
                LOG.debug("Status is resource shortage and no feasible re-allocation solution");
                status = AllocResult.Status.INFEASIBLE;
            }

        } else if (status.equals(AllocResult.Status.OVERPROVISIONING)) {
            LOG.debug("Status is resource over-provisioning");
            adjustedAllocation = getRemovedAllocationGeneralTopApplyMMK(
                    realLatencyMilliSeconds, estTotalSojournTimeMilliSec_MMK, queueingNetwork,
                    completeTimeMilliSecUpper, completeTimeMilliSecLower, currentUsedThreadByBolts, resourceUnit);
        }

        Map<String, Object> context = new HashMap<>();
        context.put("realLatency", realLatencyMilliSeconds);
        context.put("estMMK", estTotalSojournTimeMilliSec_MMK);
        context.put("urMMK", underEstimateRatio);
        context.put("reMMK", relativeError);

        LOG.info(String.format("realLatency(ms): %.4f, estMMK: %.4f, urMMK: %.4f, reMMK: %.4f, status: %s",
                realLatencyMilliSeconds, estTotalSojournTimeMilliSec_MMK, underEstimateRatio, relativeError, status.toString()));
        return new AllocResult(status, adjustedAllocation, currOptAllocation, kMaxOptAllocation).setContext(context);
    }

    /**
     *
     * @param sourceNode
     * @param queueingNetwork
     * @param completeTimeMilliSecUpper
     * @param completeTimeMilliSecLower
     * @param currBoltAllocation
     * @param maxAvailable4Bolt
     * @param currentUsedThreadByBolts
     * @param resourceUnit  it is to indicate the units of resources shall be allocated each time adding or removing
     * @return
     */
    public static AllocResult checkOptimized(
            GeneralSourceNode sourceNode, Map<String, GeneralServiceNode> queueingNetwork,
            double completeTimeMilliSecUpper, double completeTimeMilliSecLower,
            Map<String, Integer> currBoltAllocation, int maxAvailable4Bolt, int currentUsedThreadByBolts, int resourceUnit) {

        AllocResult allocResult = checkOptimized_MMK(
                sourceNode, queueingNetwork, completeTimeMilliSecUpper, completeTimeMilliSecLower, currBoltAllocation, maxAvailable4Bolt, currentUsedThreadByBolts, resourceUnit);
        LOG.info("MMK, reUnit: " + resourceUnit  +  ", alloStat: " + allocResult.status);
        LOG.info("MMK, currOptAllo: " + allocResult.currOptAllocation);
        LOG.info("MMK, adjustAllo: " + allocResult.minReqOptAllocation);
        LOG.info("MMK, kMaxOptAllo: " + allocResult.kMaxOptAllocation);

        return allocResult;
    }

    /**
     * if mu = 0.0 or serverCount not positive, then rho is not defined, we consider it as the unstable case (represented by Double.MAX_VALUE)
     * otherwise, return the calculation results. Leave the interpretation to the calling function, like isStable();
     *
     * @param lambda
     * @param mu
     * @param serverCount
     * @return
     */
    private static double calcRho(double lambda, double mu, int serverCount) {
        return (mu > 0.0 && serverCount > 0) ? lambda / (mu * (double) serverCount) : Double.MAX_VALUE;
    }

    /**
     * First call calcRho,
     * then determine when rho is validate, i.e., rho < 1.0
     * otherwise return unstable (FALSE)
     *
     * @param lambda
     * @param mu
     * @param serverCount
     * @return
     */
    public static boolean isStable(double lambda, double mu, int serverCount) {
        return calcRho(lambda, mu, serverCount) < 1.0;
    }

    private static double getRhoSingleServer(double lambda, double mu) {
        return calcRho(lambda, mu, 1);
    }

    public static int getMinReqServerCount(double lambda, double mu) {
        return (int) (lambda / mu) + 1;
    }


    /**
     * we assume the stability check is done before calling this function
     * The total sojournTime of an MMK queue is the sum of queueing time and expected service time (1.0 / mu).
     *
     * @param lambda,     average arrival rate
     * @param mu,         average execute rate
     * @param serverCount
     * @return
     */
    public static double sojournTime_MMK(double lambda, double mu, int serverCount) {
        return avgQueueingTime_MMK(lambda, mu, serverCount) + 1.0 / mu;
    }

    /**
     * we assume the stability check is done before calling this function
     * This is a simple version of approximation on average sojourn time for the G/G/K queue
     * E(Wq) = (caj+csj)/2 E(Wq(M/M/K)) --> Eq. 62 of survey paper on OQN by Gabriel Bitran
     *
     * @param lambda
     * @param scvArrival, scv of inter-arrival times
     * @param mu
     * @param scvService, scv of service times
     * @param serverCount
     * @return
     */
    public static double sojournTime_GGK_SimpleAppr(double lambda, double scvArrival, double mu, double scvService, int serverCount) {
        double adjust = (scvArrival + scvService) / 2.0;
        return avgQueueingTime_MMK(lambda, mu, serverCount) * adjust + 1.0 / mu;
    }

    /**
     * we assume the stability check is done before calling this function
     * This is a more complex and accurate version of approximation on average sojourn time for the G/G/K queue
     * E(Wq) = \Phi(rho, caj, csj, k) * (caj+csj)/2 E(Wq(M/M/K)) --> Appendix 1 of "Machine allocation algorithms for job shop manufacturing", van Vliet and R. Kan
     *
     * @param lambda
     * @param scvArrival, scv of inter-arrival times
     * @param mu
     * @param scvService, scv of service times
     * @param serverCount
     * @return
     */
    public static double sojournTime_GGK_ComplexAppr(double lambda, double scvArrival, double mu, double scvService, int serverCount) {

        double k = serverCount;
        double rho = lambda / (mu * k);
        double gamma = (1.0 - rho) * (k - 1.0) * (Math.sqrt(4.0 + 5.0 * k) - 2.0) / (16.0 * k * rho);
        gamma = Math.min(0.24, gamma);

        double f1 = 1.0 + gamma;
        double f2 = 1.0 - 4.0 * gamma;
        double f3 = f2 * Math.exp(-2.0 * (1.0 - rho) / (3.0 * rho));
        double f4 = Math.min(1.0, (f1 + f3) / 2.0);

        double adjust = (scvArrival + scvService) / 2.0;
        double Psi = adjust < 1.0 ? Math.pow(f4, 2.0 - 2.0 * adjust) : 1.0;

//        double Phy = 0.0;
//        if (scvArrival >= scvService) {
//            Phy = f1 * (scvArrival - scvService) / (scvArrival - 0.75 * scvService)
//                    + Psi * scvService / (4.0 * scvArrival - 3.0 * scvService);
//        } else {
//            Phy = f3 * 0.5 * (scvService - scvArrival) / (scvArrival + scvService)
//                    + Psi * 0.5 * (scvService + 3.0 * scvArrival) / (scvArrival + scvService);
//        }
//      simplified as:
        double Phy = (scvArrival >= scvService)
                ? (f1 * (scvArrival - scvService) / (scvArrival - 0.75 * scvService)
                + Psi * scvService / (4.0 * scvArrival - 3.0 * scvService))
                : (f3 * 0.5 * (scvService - scvArrival) / (scvArrival + scvService)
                + Psi * 0.5 * (scvService + 3.0 * scvArrival) / (scvArrival + scvService));

        return avgQueueingTime_MMK(lambda, mu, serverCount) * adjust * Phy + 1.0 / mu;
    }

    /**
     * we assume the stability check is done before calling this function
     * This is a standard erlang-C formula
     *
     * @param lambda
     * @param mu
     * @param serverCount
     * @return
     */
    public static double avgQueueingTime_MMK(double lambda, double mu, int serverCount) {
        double r = lambda / (mu * (double) serverCount);
        double kr = lambda / mu;

        double phi0_p1 = 1.0;
        for (int i = 1; i < serverCount; i++) {
            double a = Math.pow(kr, i);
            double b = (double) factorial(i);
            phi0_p1 += (a / b);
        }

        double phi0_p2_nor = Math.pow(kr, serverCount);
        double phi0_p2_denor = (1.0 - r) * (double) (factorial(serverCount));
        double phi0_p2 = phi0_p2_nor / phi0_p2_denor;

        double phi0 = 1.0 / (phi0_p1 + phi0_p2);

        double pWait = phi0_p2 * phi0;

        double waitingTime = pWait * r / ((1.0 - r) * lambda);

        return waitingTime;
    }


    public static double sojournTime_MM1(double lambda, double mu) {
        return 1.0 / (mu - lambda);
    }

    private static int factorial(int n) {
        if (n < 0) {
            throw new IllegalArgumentException("Attention, negative input is not allowed: " + n);
        } else if (n == 0) {
            return 1;
        } else {
            int ret = 1;
            for (int i = 2; i <= n; i++) {
                ret = ret * i;
            }
            return ret;
        }
    }
}
