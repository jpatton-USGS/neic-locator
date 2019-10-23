package gov.usgs.locator;

import gov.usgs.traveltime.AuxTtRef;
import gov.usgs.traveltime.TTSessionLocal;
import gov.usgs.traveltime.TTime;
import gov.usgs.traveltime.TTimeData;
import gov.usgs.traveltime.TauUtil;
import java.util.ArrayList;
import java.util.logging.Logger;

/**
 * The PhaseID class associates theoretical seismic phases with the observed seismicpicks.
 *
 * @author Ray Buland
 */
public class PhaseID {
  /** An Event object containing the event to perform phase identification upon. */
  private Event event;

  /**
   * A Hypocenter object containing the hypocenter of event to perform phase identification upon.
   */
  private Hypocenter hypo;

  /**
   * A TTSessionLocal object containing a local travel-time manager used to perform phase
   * identification.
   */
  private TTSessionLocal ttLocalSession;

  /**
   * An AuxTtRef object containing a auxiliary travel-time information used to perform phase
   * identification.
   */
  private AuxTtRef auxiliaryTTInfo;

  /**
   * An ArrayList of WeightedResidual objects containing the weighted residuals of the event picks.
   */
  private ArrayList<WeightedResidual> weightedResiduals;

  /** A PickGroup object, which holds the current pick group being processed by phaseID. */
  private PickGroup currentGroup;

  /** A Pick object containing the last pick identified. */
  private Pick lastPick = null;

  /**
   * A TTime object containing the travel time information for all possible phases generated by a
   * source at the event to the current pick group being processed by phaseID.
   */
  private TTime currentTTList = null;

  /**
   * A double containing the weight for phases that don't match, used to control figure-of-merit
   * calculations during phaseID.
   */
  private double otherWeight;

  /**
   * A double containing the weight to resist changing identification, used to control
   * figure-of-merit calculations during phaseID.
   */
  private double stickyWeight;

  /**
   * A boolean flag indicating whether the current phase being identified is a generic phase or not.
   */
  private boolean isGeneric = false;

  /**
   * A boolean flag indicating whether the current phase being identified is a primary phase or not.
   */
  private boolean isPrimary = false;

  /** A String containing the last travel time phase group name processed by phaseID. */
  private String currPhaseGroupName = null;

  /** Private logging object. */
  private static final Logger LOGGER = Logger.getLogger(PhaseID.class.getName());

  /**
   * The PhaseID constructor. This constructor sets the event and tt session to the provided values.
   *
   * @param event An Event object containing the information for the event to perform phase
   *     identification upon.
   * @param ttLocalSession A TTSessionLocal object holding the local travel-time manager
   */
  public PhaseID(Event event, TTSessionLocal ttLocalSession) {
    this.event = event;
    hypo = event.getHypo();
    this.ttLocalSession = ttLocalSession;

    if (ttLocalSession != null) {
      this.auxiliaryTTInfo = ttLocalSession.getAuxTT();
    } else {
      this.auxiliaryTTInfo = null;
    }

    weightedResiduals = event.getRawWeightedResiduals();
  }

  /**
   * The phaseID function performs the the phase identification on the event.
   *
   * @param otherWeight A double containing the weight for phases that don't match the current phase
   *     identification or the current phase group (higher weights make changing to an "other" phase
   *     easier)
   * @param stickyWeight A double containing the weight for an exact match (higher weights make
   *     changing the current identification harder.
   * @param reidentifyPhases If true, do the full phase re-identification, if false try not to
   *     change phase identifications
   * @param reweightResiduals If true, update the residual weights
   * @return True if any used pick in the group has changed significantly
   * @throws Exception On an illegal source depth
   */
  public boolean phaseID(
      double otherWeight, double stickyWeight, boolean reidentifyPhases, boolean reweightResiduals)
      throws Exception {

    LOGGER.fine("Curr loc: " + hypo);

    // Remember the figure-of-merit controls.
    this.otherWeight = otherWeight;
    this.stickyWeight = stickyWeight;

    // Initialize the changed flag.
    boolean changed = false;

    // Reinitialize the weighted residual storage.
    if (weightedResiduals.size() > 0) {
      weightedResiduals.clear();
    }

    // Set up a new travel-time session.
    ttLocalSession.newSession(
        event.getEarthModel(),
        hypo.getDepth(),
        LocUtil.PHASELIST,
        hypo.getLatitude(),
        hypo.getLongitude(),
        LocUtil.SUPRESSUNLIKELYPHASES,
        LocUtil.SUPRESSBACKBRANCHES,
        LocUtil.isTectonic,
        false);

    // Do the travel-time calculation for each pick group
    for (int j = 0; j < event.getNumStations(); j++) {
      currentGroup = event.getPickGroupList().get(j);
      Station station = currentGroup.getStation();

      LOGGER.finer(
          String.format(
              "PhaseID: %s %6.2f %6.2f %6.2f",
              station.getStationID().getStationCode(),
              currentGroup.getPicks().get(0).getTravelTime(),
              currentGroup.getDistance(),
              currentGroup.getAzimuth()));

      // For the first pick in the group, get the travel times.
      currentTTList =
          ttLocalSession.getTT(
              station.getLatitude(),
              station.getLongitude(),
              station.getElevation(),
              currentGroup.getDistance(),
              currentGroup.getAzimuth());

      // If reidentifyPhases is true, do a full phase re-identification for the
      // current group.
      // NOTE this is done using class variables rather than just passing the
      // group in, ick.  NOTE class variables are cool when there are so many
      // interlinked methods!
      if (reidentifyPhases) {
        reidentifyPhases();
      } else {
        // Otherwise, try not to re-identify the phases.
        noReidentification();
      }

      // update changed flag
      if (currentGroup.updatePhaseIdentifications(reweightResiduals, weightedResiduals)) {
        changed = true;
      }
    }

    // Add the Bayesian depth.
    weightedResiduals.add(
        new WeightedResidual(
            null,
            hypo.getBayesianDepthResidual(),
            hypo.getBayesianDepthWeight(),
            true,
            0d,
            0d,
            1d,
            0d,
            0d));

    // Save a copy of weightedResiduals in the original order.
    event.saveWeightedResiduals();

    // Update the station statistics.
    event.computeStationStats();

    return changed;
  }

  /**
   * This function tries to re-identifys only if the identification is invalid. During the location
   * iteration, we don't want to re-identify phases, but sometimes re-identification is thrust upon
   * us (e.g., when the depth or distance changes and the former identification no longer exists).
   */
  private void noReidentification() {
    // NOTE this depends on the current group being set by phaseID
    // Loop over picks in the group.
    for (int j = 0; j < currentGroup.getNumPicks(); j++) {
      Pick pick = currentGroup.getPick(j);
      String phCode = pick.getCurrentPhaseCode();

      if (!"".equals(phCode)) {
        // If we have a non-blank phase code, find the phase of the same name
        // that is closest to the pick in time.
        int ttIndex = -1;
        double minResidual = TauUtil.DMAX;

        for (int i = 0; i < currentTTList.getNumPhases(); i++) {
          TTimeData travelTime = currentTTList.getPhase(i);

          if (phCode.equals(travelTime.getPhCode())
              && (Math.abs(pick.getTravelTime() - travelTime.getTT()) < minResidual)) {
            ttIndex = i;
            minResidual = Math.abs(pick.getTravelTime() - travelTime.getTT());
          }
        }

        // If it's not too out of whack, force the association.
        if (ttIndex >= 0
            && (minResidual <= LocUtil.ASSOCTOLERANCE
                || "Lg".equals(phCode)
                || "LR".equals(phCode))) {
          pick.setTTStatisticalMinFoM(currentTTList.getPhase(ttIndex));
          pick.setStatisticalFoM(minResidual);
          pick.setForceAssociation(true);

          LOGGER.finer(
              String.format(
                  "NoReID: got it %s %s %6.2f %2d",
                  pick.getStation().getStationID().getStationCode(), phCode, minResidual, ttIndex));
        } else {
          // If the easy way doesn't work, we have to try harder.
          // If we have a non-blank phase code, find the phase of the same name
          // that is closest to the pick in time.
          String phaseGroupName = auxiliaryTTInfo.findGroup(phCode, false);
          ttIndex = -1;
          minResidual = TauUtil.DMAX;

          for (int i = 0; i < currentTTList.getNumPhases(); i++) {
            TTimeData travelTime = currentTTList.getPhase(i);

            if ((phaseGroupName.equals(travelTime.getPhGroup()))
                && (Math.abs(pick.getTravelTime() - travelTime.getTT()) < minResidual)) {
              ttIndex = i;
              minResidual = Math.abs(pick.getTravelTime() - travelTime.getTT());
            }
          }

          // If it's not too out of whack, force the association.
          if (ttIndex >= 0 && minResidual <= LocUtil.ASSOCTOLERANCE) {
            pick.setTTStatisticalMinFoM(currentTTList.getPhase(ttIndex));
            pick.setStatisticalFoM(minResidual);
            pick.setForceAssociation(true);

            LOGGER.finer(
                String.format(
                    "NoReID: group %s %s -> %s %6.2f %2d",
                    pick.getStation().getStationID().getStationCode(),
                    phCode,
                    currentTTList.getPhase(ttIndex).getPhCode(),
                    minResidual,
                    ttIndex));
          } else {
            if (pick.getIsUsed()) {
              LOGGER.finer("NoReID: give up " + pick.getStation().getStationID().getStationCode());

              currentGroup.initializeFoM(j, j);
              reidentifyPhases();
            } else {
              pick.setTTStatisticalMinFoM(null);
            }
          }
        } // end else phase out of wack
      } // end if phase code blank
    } // end loop over picks in the group.
  }

  /**
   * This more sophisticated phase identification is used once we have a decent initial location.
   * Note that for a full phase re-identification the weights are always updated.
   */
  private void reidentifyPhases() {
    // Initialize the figure-of-merit memory.
    currentGroup.initializeFoM(0, currentGroup.getNumPicks());

    // Pre-identify surface waves identified by trusted sources.
    for (int j = 0; j < currentGroup.getNumPicks(); j++) {
      Pick pick = currentGroup.getPick(j);

      if (pick.getIsSurfaceWave()) {
        for (int i = 0; i < currentTTList.getNumPhases(); i++) {
          if (pick.getBestPhaseCode().equals(currentTTList.getPhase(i).getPhCode())) {
            pick.setTTStatisticalMinFoM(currentTTList.getPhase(i));
            pick.setForceAssociation(true);
            break;
          }
        }
      }
    }

    // Split the theoretical phase into clusters (groups isolated in
    // travel time).
    LOGGER.finer("Clusters:");

    int i = 0;
    TTimeData travelTime = currentTTList.getPhase(0);
    double minTTWindow = travelTime.getTT() - travelTime.getWindow();
    double maxTTWindow = travelTime.getTT() + travelTime.getWindow();
    int firstTTIndex = 0; // Index of the first theoretical arrival
    int numTT = 1; // Number of theoretical arrivals
    int firstPhaseIndex = -1; // Index of the first phase within this phase group
    int numPicks = 0; // Number of picks

    // Loop over theoretical arrivals.
    for (int j = 1; j < currentTTList.getNumPhases(); j++) {
      travelTime = currentTTList.getPhase(j);

      // If this is part of the same cluster, extend the window.
      if (travelTime.getTT() - travelTime.getWindow() <= maxTTWindow) {
        minTTWindow = Math.min(minTTWindow, travelTime.getTT() - travelTime.getWindow());
        maxTTWindow = Math.max(maxTTWindow, travelTime.getTT() + travelTime.getWindow());
        numTT++;
      } else {
        // This theoretical cluster is done, now associate picks within
        // the current pick group.
        for (; i < currentGroup.getNumPicks(); i++) {
          Pick pick = currentGroup.getPick(i);

          if (pick.getTravelTime() <= maxTTWindow) {
            if (pick.getTravelTime() >= minTTWindow) {
              if (numPicks == 0) {
                firstPhaseIndex = i;
              }
              numPicks++;
            }
          } else {
            break;
          }
        }

        // If this cluster has picks, do the identification.
        if (numPicks > 0) {
          // Print the current cluster.
          LOGGER.finer(
              String.format(
                  "TT: %2d %2d  Pick: %2d %2d  Win: %7.2f %7.2f",
                  firstTTIndex, numTT, firstPhaseIndex, numPicks, minTTWindow, maxTTWindow));

          // Initialize the cumulative figure-of-merit.
          currentGroup.setCumulativeFoM(0d);

          // Do the identification.
          genPhasePermutations(firstPhaseIndex, numPicks, firstTTIndex, numTT);
        }

        // Quit if we're out of picks.
        if (i >= currentGroup.getNumPicks()) {
          break;
        }

        // Otherwise, set up for the next cluster.
        minTTWindow = travelTime.getTT() - travelTime.getWindow();
        maxTTWindow = travelTime.getTT() + travelTime.getWindow();
        firstTTIndex = j;
        numTT = 1;
        firstPhaseIndex = -1;
        numPicks = 0;
      }
    }

    // Apply the distance correction to the first arriving phase.
    double distanceCorrection = LocUtil.computeDistCorr(currentGroup.getDistance());
    if (distanceCorrection > 1d) {
      if (currentGroup.getPick(0).getTTStatisticalMinFoM() != null) {
        currentGroup
            .getPick(0)
            .setStatisticalFoM(currentGroup.getPick(0).getStatisticalFoM() / distanceCorrection);
      }
    }
  }

  /**
   * This function generates combinations of picks or theoretical arrivals to compare with all
   * possible combinations of theoretical arrivals or picks. For example, if there are 3 picks and 5
   * theoretical arrivals, the theoretical arrivals will be taken 3 at a time until all possible
   * combinations in the original order have been generated. Each combination will be treated as a
   * trial phase identification of the picks to those theoretical arrivals.
   *
   * @param firstPhaseIndex An int containing the index of the first phase within this phase group
   *     that will be part of this phase identification
   * @param numPicks An int holding the number of picks to include in this phase identification
   * @param firstTTIndex An int containing the index of the first theoretical arrival that will be
   *     part of this phase identification
   * @param numTT An int holding the number of theoretical arrivals to include in this phase
   *     identification
   */
  private void genPhasePermutations(
      int firstPhaseIndex, int numPicks, int firstTTIndex, int numTT) {
    // Set up some pointer arrays to work with internally.
    Pick[] obsPicks = new Pick[numPicks];
    int i = firstPhaseIndex;
    for (int j = 0; j < numPicks; j++, i++) {
      obsPicks[j] = currentGroup.getPicks().get(i);
    }

    TTimeData[] ttArrivals = new TTimeData[numTT];
    i = firstTTIndex;
    for (int j = 0; j < numTT; j++, i++) {
      ttArrivals[j] = currentTTList.getPhase(i);
    }

    LOGGER.finer(String.format(" Permut: %2d Picks, %2d TTs", numPicks, numTT));

    // The algorithm depends on which group is the most numerous.
    if (numTT >= numPicks) {
      // Generate the combinations.
      TTimeData[] ttPermutation = new TTimeData[numPicks];
      genKPermutationsOfN(ttArrivals, numPicks, 0, ttPermutation, obsPicks);
    } else {
      // Generate the combinations.
      Pick[] pickPermutation = new Pick[numTT];
      genKPermutationsOfN(obsPicks, numTT, 0, pickPermutation, ttArrivals);
    }
  }

  /**
   * This function creates all k-permutations of n objects, where k is the length of ttPermutation
   * and n is the length of ttGrp. Note that this algorithm is recursive. The variables length and
   * startIndex are primarily for internal use. For the caller, length should be the length of the
   * result (ttPermutation) and startIndex should be 0. This algorithm has been taken from
   * StackOverflow. It was posted by user935714 on 20 April 2016.
   *
   * @param ttArrivals A TTimeData[] containing an array of the theoretical arrivals
   * @param length An int containing the the length of the permutation subset
   * @param startIndex An int containing the starting index of the permutation subset
   * @param ttPermutation A TTimeData[] containing the results of the permutation
   * @param obsPicks A Pick[] containing the array of observed picks
   */
  private void genKPermutationsOfN(
      TTimeData[] ttArrivals,
      int length,
      int startIndex,
      TTimeData[] ttPermutation,
      Pick[] obsPicks) {
    if (length == 0) {
      computeCombinedFoM(obsPicks, ttPermutation);
      return;
    }

    for (int i = startIndex; i <= ttArrivals.length - length; i++) {
      ttPermutation[ttPermutation.length - length] = ttArrivals[i];
      genKPermutationsOfN(ttArrivals, length - 1, i + 1, ttPermutation, obsPicks);
    }
  }

  /**
   * This function creates all k-permutations of n objects, where k is the length of pickPermutation
   * and n is the length of pickGrp. Note that this algorithm is recursive. The variables length and
   * startIndex are primarily for internal use. For the caller, length should be the length of the
   * result (pickPermutation) and startIndex should be 0. This algorithm has been taken from
   * StackOverflow. It was posted by user935714 on 20 April 2016.
   *
   * @param obsPicks A Pick[] containing the array of observed picks
   * @param length An int containing the the length of the permutation subset
   * @param startIndex An int containing the starting index of the permutation subset
   * @param pickPermutation A Pick[] containing the results of the permutation
   * @param ttArrivals A TTimeData[] containing an array of the theoretical arrivals
   */
  private void genKPermutationsOfN(
      Pick[] obsPicks, int length, int startIndex, Pick[] pickPermutation, TTimeData[] ttArrivals) {
    if (length == 0) {
      computeCombinedFoM(pickPermutation, ttArrivals);
      return;
    }

    for (int i = startIndex; i <= obsPicks.length - length; i++) {
      pickPermutation[pickPermutation.length - length] = obsPicks[i];
      genKPermutationsOfN(obsPicks, length - 1, i + 1, pickPermutation, ttArrivals);
    }
  }

  /**
   * This function computes the combined figure(s)-of-merit and saves the best identification
   * results in the picks for later processing for each trial set of phase identifications.
   *
   * @param obsPicks A Pick[] containing the array of observed picks
   * @param ttArrivals A TTimeData[] containing an array of the theoretical arrivals
   */
  private void computeCombinedFoM(Pick[] obsPicks, TTimeData[] ttArrivals) {
    // Make a pass computing the cumulative statistical figure-of-merit.
    double cumulativeFoM = 1d;

    for (int j = 0; j < ttArrivals.length; j++) {
      if (!obsPicks[j].getIsSurfaceWave()) {
        // Compute the figure-of-merit for the primary criteria.
        double probability =
            LocUtil.computePDFResValue(
                obsPicks[j].getTravelTime() - ttArrivals[j].getTT(), 0d, ttArrivals[j].getSpread());
        double observabilityAmp = computeObsAmplitude(obsPicks[j], ttArrivals[j]);
        double residual = computeResidual(obsPicks[j], ttArrivals[j]);
        double boost = LocUtil.computeProximityBoost(residual);

        LOGGER.finer(
            String.format(
                "%s %s: %10.4e %10.4e %5.2f",
                obsPicks[j].getBestPhaseCode(),
                ttArrivals[j].getPhCode(),
                probability,
                observabilityAmp,
                boost));

        cumulativeFoM *= observabilityAmp * probability * boost;
      }
    }

    LOGGER.finest(
        String.format("Cum: %10.4e %10.4e", cumulativeFoM, currentGroup.getCumulativeFoM()));

    // Make a second pass if this is the highest figure-of-merit yet.  Note,
    // the Fortran version has greater than or equal to.
    if (cumulativeFoM > currentGroup.getCumulativeFoM()) {
      currentGroup.setCumulativeFoM(cumulativeFoM);

      for (int j = 0; j < ttArrivals.length; j++) {
        if (!obsPicks[j].getIsSurfaceWave()) {
          obsPicks[j].setStatisticalFoM(ttArrivals[j], computeResidual(obsPicks[j], ttArrivals[j]));
        }
      }
    }
  }

  /**
   * This function computes the modified theoretical phase "amplitude". This is the phase
   * observability with empirical modifiers to reflect how closely it matches the observed phase.
   * Note that this is a complicated function of who identified the observed phase, if they are in
   * the same phase group, and if they have the same phase type. The sticky weight promotes
   * stability by tending to keep the old identification all else being equal.
   *
   * @param pick A Pick object containing the pick information for one pick
   * @param travelTime A TTimeData object holding arrival time information for one arrival
   * @return A double containing the "amplitude" or observability which has been modified by
   *     empirical weights
   */
  private double computeObsAmplitude(Pick pick, TTimeData travelTime) {
    // Set up the observed pick phase group.
    // Note depends on the lastPick class variable being set
    if (!pick.equals(lastPick)) {
      lastPick = pick;
      currPhaseGroupName =
          auxiliaryTTInfo.findGroup(
              pick.getBestPhaseCode(), (pick.getOriginalAuthorType() == AuthorType.CONTRIB_AUTO));
      isPrimary = auxiliaryTTInfo.isPrimary();

      if ("Any".equals(currPhaseGroupName) || pick.getBestPhaseCode().equals(currPhaseGroupName)) {
        isGeneric = true;
      } else {
        isGeneric = false;
      }

      LOGGER.finest("New " + currPhaseGroupName);
      if (isPrimary) {
        LOGGER.finest(" Pri");
      }
      if (isGeneric) {
        LOGGER.finest(" Gen");
      }
    } else {
      LOGGER.finest("Old");
    }

    // initialize the observability amplitude.
    double observabilityAmp;
    if (!travelTime.getDis()) {
      observabilityAmp = travelTime.getObserv();
    } else {
      observabilityAmp = LocUtil.DOWNWEIGHT * travelTime.getObserv();
      LOGGER.finest(" Down");
    }

    // Do the group logic.  If the phase codes match drop through
    // unless the phase might be generic.
    if ((!pick.getBestPhaseCode().equals(travelTime.getPhCode()) || isGeneric)
        && !"Any".equals(currPhaseGroupName)) {
      // Handle primary groups differently for generic phase codes.
      if (isGeneric && isPrimary) {
        // If the observed phase group matches the primary or auxiliary
        // groups of the theoretical phase use the group weighting.  That
        // is, a generic P might be either a P or a PKP.  The "Reg" group
        // is a special case for contributed automatic picks (typically
        // from regional networks) which are assumed to be regional.
        if (currPhaseGroupName.equals(travelTime.getPhGroup())
            || currPhaseGroupName.equals(travelTime.getAuxGroup())
            || ("Reg".equals(currPhaseGroupName) && travelTime.isRegional())) {
          observabilityAmp *= LocUtil.GROUPWEIGHT;

          LOGGER.finest(" Group1");
        } else {
          // Otherwise use the other (non-group) weighting.
          observabilityAmp *= otherWeight;

          LOGGER.finest(" Other1");

          // If we trust the phase identification and the arrival types
          // of the phases don't match, make re-identifying even harder
          if (!pick.getIsAutomatic()
              && TauUtil.arrivalType(currPhaseGroupName)
                  != TauUtil.arrivalType(travelTime.getPhCode())) {
            observabilityAmp *= LocUtil.TYPEWEIGHT;

            LOGGER.finest(" Type1");
          }
        }
      } else {
        // If the observed phase group matches the primary group of the
        // theoretical phase use the group weighting.  That is, a Pn would
        // be in the same group as Pg, but not PKPdf.  Note that a generic
        // PKP would only match PKP phases.
        if (currPhaseGroupName.equals(travelTime.getPhGroup())) {
          observabilityAmp *= LocUtil.GROUPWEIGHT;

          LOGGER.finest(" Group2");
        } else {
          // Otherwise use the other (non-group) weighting.
          observabilityAmp *= otherWeight;

          LOGGER.finest(" Other2");

          // If we trust the phase identification and the arrival types
          // of the phases don't match, make re-identifying even harder
          if (!pick.getIsAutomatic()
              && TauUtil.arrivalType(currPhaseGroupName)
                  != TauUtil.arrivalType(travelTime.getPhCode())) {
            observabilityAmp *= LocUtil.TYPEWEIGHT;

            LOGGER.finest(" Type2");
          }
        }
      }
    }

    // Account for the affinity.
    if (pick.getBestPhaseCode().equals(travelTime.getPhCode())) {
      observabilityAmp *= pick.getOriginalPhaseAffinity();

      LOGGER.finest(" Aff");
    }

    // Make the existing identification harder to change.
    if (pick.getCurrentPhaseCode().equals(travelTime.getPhCode())) {
      observabilityAmp *= stickyWeight;

      LOGGER.finest(" Sticky");
    }

    return observabilityAmp;
  }

  /**
   * This function computes the affinity weighted travel-time residual.
   *
   * @param pick A Pick object containing the pick information for one pick
   * @param travelTime A TTimeData object holding arrival time information for one arrival
   * @return A double containing the affinity weighted residual
   */
  private double computeResidual(Pick pick, TTimeData travelTime) {
    if (pick.getBestPhaseCode().equals(travelTime.getPhCode())) {
      return Math.abs(pick.getTravelTime() - travelTime.getTT()) / pick.getOriginalPhaseAffinity();
    } else {
      return Math.abs(pick.getTravelTime() - travelTime.getTT()) / LocUtil.NULLAFFINITY;
    }
  }
}
