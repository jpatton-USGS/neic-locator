package gov.usgs.locator;

/**
 * The Stepper class manages the rank-sum-estimator logic needed to refine the hypocenter.
 *
 * @author Ray Buland
 */
public class Stepper {
  /** An Event object containing the event to use when performing Stepper calculations. */
  private Event event;

  /**
   * A Hypocenter object containing the hypocenter of the event to use when performing Stepper
   * calculations.
   */
  private Hypocenter hypo;

  /**
   * An AuxLocRef object containing auxiliary locator information used when performing Stepper
   * calculations.
   */
  private AuxLocRef auxLoc;

  /** A Cratons object holding the geographic boundaries of continental cratons. */
  private Cratons cratons;

  /** A ZoneStats object containing earthquake statistics by geographic location. */
  private ZoneStats zoneStats;

  /** A PhaseID object containing the phase identification logic. */
  private PhaseID phaseIDLogic;

  /** A LinearStep object used for various travel time computations. */
  private LinearStep linearStep;

  /** A RSumEstResult object holding the most recent rank-sum estimation rSumEstResult. */
  private RSumEstResult rSumEstResult;

  /** A HypoAudit object holding the most hypocenter audit record. */
  private HypoAudit lastHypoAudit;

  /** A RankSumEstimator object used for the rank-sum estimation of the raw picks. */
  private RankSumEstimator rawRankSumEstimator;

  /** A RankSumEstimator object used for the rank-sum estimation of the projected picks. */
  private RankSumEstimator projectedRankSumEstimator;

  /** A Decorrelator object used when decorrelating the event picks. */
  private Decorrelator decorrelator;

  /**
   * The Stepper constructor. Set the event, phaseID logic, and auxiliary locator information to the
   * provided values
   *
   * @param event An Event object containing the event to use when performing Stepper calculations.
   * @param phaseIDLogic A PhaseID object containing the phase identification logic.
   * @param auxLoc An AuxLocRef object containing auxiliary locator information used when performing
   *     Stepper calculations.
   */
  public Stepper(Event event, PhaseID phaseIDLogic, AuxLocRef auxLoc) {
    this.event = event;
    hypo = event.getHypo();
    this.auxLoc = auxLoc;
    cratons = auxLoc.getCratons();
    zoneStats = auxLoc.getZoneStats();
    this.phaseIDLogic = phaseIDLogic;
    rawRankSumEstimator = event.getRawRankSumEstimator();
    projectedRankSumEstimator = event.getProjectedRankSumEstimator();
    linearStep = new LinearStep(event);
    decorrelator = event.getDecorrelator();
  }

  /**
   * The Stepper phase identification function. Sets the tectonic flag and Bayesian depth
   * parameters. Calculate the median residual (origin time correction), rank-sum-estimator
   * dispersion, and rank-sum-estimator direction of steepest descents. This initial version also
   * sets the reference dispersion value in the hypocenter.
   *
   * @param otherWeight A double value holding the weight for phases that don't match the current
   *     phase identification or the current phase group (higher weights make changing to an "other"
   *     phase easier)
   * @param stickyWeight A double value holding the weight for an exact match (higher weights make
   *     changing the current identification harder.
   * @param reidentifyPhases A boolean flag indicating whether to perform a full phase
   *     re-identification, if false try not to change identifications
   * @param updateResWeights A boolean flag indicating whether to the residual weights
   * @return A LocStatus object containing the status value.
   * @throws Exception On an illegal source depth
   */
  public LocStatus doPhaseIdentification(
      double otherWeight, double stickyWeight, boolean reidentifyPhases, boolean updateResWeights)
      throws Exception {
    LocStatus status =
        internalPhaseID(otherWeight, stickyWeight, reidentifyPhases, updateResWeights);

    if (status == LocStatus.SUCCESS) {
      hypo.setEstimatorDispersionValue(rSumEstResult.getDispersion());
    }

    return status;
  }

  /**
   * The Stepper internal phase identification function. Sets the tectonic flag and Bayesian depth
   * parameters. Then calculates the median residual (origin time correction), rank-sum-estimator
   * dispersion, and rank-sum-estimator direction of steepest descents. For calls from makeStep, we
   * don't want to update the reference dispersion in the hypocenter just yet.
   *
   * @param otherWeight A double value holding the weight for phases that don't match the current
   *     phase identification or the current phase group (higher weights make changing to an "other"
   *     phase easier)
   * @param stickyWeight A double value holding the weight for an exact match (higher weights make
   *     changing the current identification harder.
   * @param reidentifyPhases A boolean flag indicating whether to perform a full phase
   *     re-identification, if false try not to change identifications
   * @param updateResWeights A boolean flag indicating whether to the residual weights
   * @return A LocStatus object containing the status value.
   * @throws Exception On an illegal source depth
   */
  private LocStatus internalPhaseID(
      double otherWeight, double stickyWeight, boolean reidentifyPhases, boolean updateResWeights)
      throws Exception {
    // Set the location environment.
    if (updateResWeights) {
      setLocEnvironment();
    }

    // Reidentify phases.
    event.setHasPhaseIdChanged(
        phaseIDLogic.phaseID(otherWeight, stickyWeight, reidentifyPhases, updateResWeights));

    // Bail on insufficient data.
    if (event.getNumStationsUsed() < 3) {
      return LocStatus.INSUFFICIENT_DATA;
    }

    double residualsMedian;
    double dispersion;
    if (LocUtil.useDecorrelation) {
      // Demedian the raw residuals.
      residualsMedian = rawRankSumEstimator.computeMedian();
      rawRankSumEstimator.deMedianResiduals();

      if (LocUtil.deBugLevel > 0) {
        System.out.format("Lsrt: EL av = %8.4f\n", residualsMedian);
      }

      // Decorrelate the raw data.
      if (event.getHasPhaseIdChanged()) {
        decorrelator.decorrelate();
      }
      decorrelator.projectPicks();

      // Get the median of the projected data.
      double projectedMedian = projectedRankSumEstimator.computeMedian();

      // Demedian the projected design matrix.
      projectedRankSumEstimator.deMedianDesignMatrix();

      // Get the rank-sum-estimator dispersion of the projected data.
      dispersion = projectedRankSumEstimator.computeDispersionValue();

      if (LocUtil.deBugLevel > 0) {
        System.out.format("Lsrt: ST av chisq = %8.4f %10.4f\n", projectedMedian, dispersion);
      }

      // Get the steepest descent direction.
      hypo.setStepDirectionUnitVector(
          projectedRankSumEstimator.compSteepestDescDir(hypo.getDegreesOfFreedom()));
    } else {
      // Demedian the raw residuals.
      residualsMedian = rawRankSumEstimator.computeMedian();

      rawRankSumEstimator.deMedianResiduals();

      // Demedian the raw design matrix.
      rawRankSumEstimator.deMedianDesignMatrix();

      // Get the rank-sum-estimator dispersion of the raw data.
      dispersion = rawRankSumEstimator.computeDispersionValue();

      if (LocUtil.deBugLevel > 0) {
        System.out.format("Lsrt: ST av chisq = %8.4f %10.4f\n", residualsMedian, dispersion);
      }

      // Get the steepest descent direction.
      hypo.setStepDirectionUnitVector(
          rawRankSumEstimator.compSteepestDescDir(hypo.getDegreesOfFreedom()));
    }

    if (LocUtil.deBugLevel > 0) {
      System.out.print("Adder: b =");

      for (int j = 0; j < hypo.getStepDirectionUnitVector().length; j++) {
        System.out.format(" %7.4f", hypo.getStepDirectionUnitVector()[j]);
      }

      System.out.println();
    }

    rSumEstResult = new RSumEstResult(0d, residualsMedian, 0d, dispersion);

    return LocStatus.SUCCESS;
  }

  /**
   * This function makes a step from the current hypocenter to the optimal rank-sum-estimator
   * dispersion minimum based on linearized residual estimates.
   *
   * @param stage An int containing the current stage
   * @param iteration An int containing the current iteration within the stage
   * @return A LocStatus object holding the current stepper status
   * @throws Exception If the linearized step length bisection algorithm fails
   */
  public LocStatus makeStep(int stage, int iteration) throws Exception {
    LocStatus status = LocStatus.SUCCESS;

    // Save the current hypocenter as a reference for the step length damping.
    lastHypoAudit = new HypoAudit(hypo, 0, 0, event.getNumPhasesUsed(), status);

    // Get the linearized step.
    hypo.setNumOfTimesStepLengthDampening(0);
    double damp = LocUtil.computeDampeningFactor();
    hypo.setStepLength(Math.max(hypo.getStepLength(), 2d * LocUtil.CONVERGENCESTAGELIMITS[stage]));
    rSumEstResult =
        linearStep.stepLength(
            hypo.getStepDirectionUnitVector(),
            hypo.getStepLength(),
            LocUtil.CONVERGENCESTAGELIMITS[stage],
            LocUtil.STEPLENSTAGELIMITS[stage],
            hypo.getEstimatorDispersionValue());

    // This weird special case appears once in a while.
    if (rSumEstResult.getDispersion() >= hypo.getEstimatorDispersionValue()
        && rSumEstResult.getStepLength() < LocUtil.CONVERGENCESTAGELIMITS[stage]) {
      hypo.setStepLength(rSumEstResult.getStepLength());
      hypo.setHorizontalStepLength(0d);
      hypo.setVerticalStepLength(0d);

      logStep("Step", stage, iteration, status);

      return status;
    }

    // Update the hypocenter.
    hypo.setLinearTimeShiftEstimate(rSumEstResult.getMedianResidual());
    event.updateHypo(rSumEstResult.getStepLength(), rSumEstResult.getMedianResidual());

    // Reidentify phases and get the non-linear rank-sum-estimator parameters
    // for the new hypocenter.
    if (internalPhaseID(0.01d, 5d, false, false) == LocStatus.INSUFFICIENT_DATA) {
      return LocStatus.INSUFFICIENT_DATA;
    }
    event.updateOriginTime(rSumEstResult.getMedianResidual());

    // If the phase identification has changed, we have to start over.
    if (event.getHasPhaseIdChanged()) {
      hypo.setEstimatorDispersionValue(rSumEstResult.getDispersion());
      status = LocStatus.PHASEID_CHANGED;
      logStep("ReID", stage, iteration, status);
      return status;
    }

    // If we're headed down hill, this iteration is done.
    if (rSumEstResult.getDispersion() < hypo.getEstimatorDispersionValue()) {
      hypo.setEstimatorDispersionValue(rSumEstResult.getDispersion());
      logStep("Step", stage, iteration, status);
      return status;
    }

    // Damp the solution.  Damping is necessary if the linearized step increases
    // the rank-sum-estimator dispersion (variously called
    // computeDispersionValue and dispersion here).  However, it is observed to
    // be highly unstable, hence the complicated factor to determine the damping
    // factor and the elaborate means to trap a failure.  Note that the damping
    // factor is only updated once per call to makeStep.  This is because the
    // infinite loop we're avoiding depends on a cycle comprised of a normal
    // step and a damped step.
    do {
      // Trap a failed damping strategy.
      if (damp * hypo.getStepLength() <= LocUtil.CONVERGENCESTAGELIMITS[stage]
          || (hypo.getNumOfTimesStepLengthDampening() > 0
              && LocUtil.compareHypos(hypo, lastHypoAudit))) {
        // We've damped the solution into oblivion.  Give up.
        hypo.resetHypo(lastHypoAudit);
        hypo.setHorizontalStepLength(0d);
        hypo.setVerticalStepLength(0d);

        // Set the exit status.
        if (rSumEstResult.getDispersion()
                <= LocUtil.ALMOSTCONVERGED * hypo.getEstimatorDispersionValue()
            && hypo.getStepLength() <= LocUtil.CONVERGENCESTAGELIMITS[stage]) {
          status = LocStatus.NEARLY_CONVERGED;
        } else if (hypo.getStepLength() <= LocUtil.STEPTOLERANCE) {
          status = LocStatus.DID_NOT_CONVERGE;
        } else {
          status = LocStatus.UNSTABLE_SOLUTION;
        }

        logStep("Fail", stage, iteration, status);

        return status;
      }

      // Do the damping.
      hypo.setNumOfTimesStepLengthDampening(hypo.getNumOfTimesStepLengthDampening() + 1);
      hypo.resetHypo(lastHypoAudit);
      hypo.setStepLength(hypo.getStepLength() * damp);
      hypo.setLinearTimeShiftEstimate(hypo.getLinearTimeShiftEstimate() * damp);

      // Update the hypocenter.
      event.updateHypo(hypo.getStepLength(), hypo.getLinearTimeShiftEstimate());

      // Reidentify phases and get the non-linear rank-sum-estimator parameters
      // for the new hypocenter.
      if (internalPhaseID(0.01d, 5d, false, false) == LocStatus.INSUFFICIENT_DATA) {
        return LocStatus.INSUFFICIENT_DATA;
      }
      event.updateOriginTime(rSumEstResult.getMedianResidual());

      // If the phase identification has changed, we have to start over.
      if (event.getHasPhaseIdChanged()) {
        hypo.setEstimatorDispersionValue(rSumEstResult.getDispersion());
        status = LocStatus.PHASEID_CHANGED;

        logStep("ReID", stage, iteration, status);

        return status;
      }

      logStep("Damp", stage, iteration, status);
    } while (rSumEstResult.getDispersion() >= hypo.getEstimatorDispersionValue());

    return status;
  }

  /**
   * This function sets the location environment by determining if the location is in a craton or
   * tectonic area and setting the Bayesian depth.
   */
  protected void setLocEnvironment() {
    // Set the tectonic flag.  Note that everything outside cratons
    // is considered tectonic.
    if (auxLoc.getCratons().isCraton(hypo.getLatitude(), hypo.getLongitude())) {
      LocUtil.isTectonic = false;
    } else {
      LocUtil.isTectonic = true;
    }

    if (LocUtil.deBugLevel > 0) {
      System.out.println("\n\tTectonic = " + LocUtil.isTectonic);
    }

    if (!event.getIsDepthManual()) {
      // Update the Bayesian depth if it wasn't set by the analyst.
      double bayesDepth = zoneStats.getBayesDepth(hypo.getLatitude(), hypo.getLongitude());
      double bayesSpread = zoneStats.getBayesSpread();
      hypo.updateBayes(bayesDepth, bayesSpread);
    }
    if (LocUtil.deBugLevel > 0) {
      System.out.format(
          "\tBayes: %5.1f %5.3f %b\n",
          hypo.getBayesianDepth(), hypo.getBayesianDepthWeight(), event.getIsDepthManual());
    }
  }

  /**
   * This function logs the current step. Note that this logging is the principle means of debugging
   * Locator problems. In the long run, this output should be entered into the error log and only
   * printed for a debug level of at least one.
   *
   * @param id A string containing the id for the log entry
   * @param stage An int containing the current stage
   * @param iteration An int containing the current iteration within the stage
   * @param status A LocStatus object holding the current stepper status
   */
  private void logStep(String id, int stage, int iteration, LocStatus status) {
    int used;
    if (LocUtil.useDecorrelation) {
      used = event.getNumProjectedPhasesUsed();
    } else {
      used = event.getNumPhasesUsed();
    }

    if (used >= hypo.getDegreesOfFreedom()) {
      hypo.setEstimatorRMSEquivalent(
          hypo.getEstimatorDispersionValue() / (used - hypo.getDegreesOfFreedom() + 1));
    } else {
      hypo.setEstimatorRMSEquivalent(0d);
    }

    if (LocUtil.deBugLevel > 0) {
      System.out.format(
          "\n%s: %1d %2d %5d %8.4f %8.4f %6.2f del= %5.1f %6.1f " + "rms= %6.2f %s\n",
          id,
          stage,
          iteration,
          used,
          hypo.getLatitude(),
          hypo.getLongitude(),
          hypo.getDepth(),
          hypo.getHorizontalStepLength(),
          hypo.getVerticalStepLength(),
          hypo.getEstimatorRMSEquivalent(),
          status);
    }
  }
}
