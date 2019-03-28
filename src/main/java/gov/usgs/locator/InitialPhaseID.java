package gov.usgs.locator;

import gov.usgs.traveltime.TTSessionLocal;
import gov.usgs.traveltime.TTime;
import gov.usgs.traveltime.TTimeData;
import java.util.ArrayList;

/**
 * The InitialPhaseID class performs an initial phase identification before any 
 * location iterations or real phase identification takes place. This initial 
 * pass ensures that we have something reasonable to work with by emphasizing 
 * crust and mantle P waves and manually identified phases.  If there are a lot 
 * of apparently misidentified first arrivals, the algorithm gets even more 
 * draconian.
 * 
 * @author Ray Buland
 *
 */
public class InitialPhaseID {
  /**
   * An Event object containing the event to perform initial phase 
   * identification upon.
   */
  private Event event;

  /**
   * A Hypocenter object containing the hypocenter of event to perform initial  
   * phase identification upon.
   */  
  private Hypocenter hypo;

  /**
   * A TTSessionLocal object containing a local travel-time manager used to  
   * perform initial phase identification.
   */    
  private TTSessionLocal ttLocalSession;

  /**
   * A PhaseID object containing Phase identification logic used in  
   * performing initial phase identification.
   */  
  private PhaseID phaseID;

  /** 
   * An ArrayList of Wresidual objects containing the weighted residuals of the 
   * event picks.
   */  
  private ArrayList<Wresidual> weightedResiduals;
  
  /**
   * A Restimator object used for the rank-sum estimation of the picks to 
   * refine the initial phase identification.
   */
  private Restimator rankSumEstimator;
  
  /**
   * A Stepper object used to manage the rank-sum estimation logic needed to 
   * refine the initial phase identification.
   */
  private Stepper stepper;

  /**
   * The InitialPhaseID constructor. This constructor sets the event, tt session, 
   * phase identification logic, and rank-sum estimator to the provided values.
   * 
   * @param event An Event object containing the information for the event to 
   *               perform initial phase identification upon.
   * @param ttLocalSession A TTSessionLocal object holding the local travel-time 
   *                        manager
   * @param phaseID A PhaseID object containing the phase identification logic 
   *                 for initial phase identification
   * @param stepper A Restimator object containing the rank-sum estimation 
   *                 driver logic
   */
  public InitialPhaseID(Event event, TTSessionLocal ttLocalSession, PhaseID phaseID, 
      Stepper stepper) {
    this.event = event;
    hypo = event.getHypo();
    this.ttLocalSession = ttLocalSession;
    this.phaseID = phaseID;
    weightedResiduals = event.getRawWeightedResiduals();
    rankSumEstimator = event.getRawRankSumEstimator();
    this.stepper = stepper;
  }
  
  /**
   * The phaseID function performs a tentative phase identification to see if 
   * the event is making sense.
   * 
   * @throws Exception On an illegal source depth
   */
  public void phaseID() throws Exception {
    int badPs = 0;
    
    // Reinitialize the weighted residual storage.
    if (weightedResiduals.size() > 0) {
      weightedResiduals.clear();
    }
    
    // Set up a new travel-time session if the depth has changed.
    ttLocalSession.newSession(event.getEarthModel(), hypo.getDepth(), 
        LocUtil.PHASELIST, hypo.getLatitude(), hypo.getLongitude(), 
        LocUtil.SUPRESSUNLIKELYPHASES, LocUtil.SUPRESSBACKBRANCHES, 
        LocUtil.isTectonic, false);
        
    // Loop over picks in the groups.
    if (LocUtil.deBugLevel > 1) {
      System.out.println();
    }

    for (int j = 0; j < event.getNumStations(); j++) {
      PickGroup group = event.getPickGroupList().get(j);
      
      if (group.picksUsed() > 0) {
        // For the first pick in the group, get the travel times.
        Station station = group.station;
        if (LocUtil.deBugLevel > 1) {
          System.out.println("InitialPhaseID: " + station + ":");
        }

        // Do the travel-time calculation.
        TTime ttList = ttList = ttLocalSession.getTT(station.latitude, 
            station.longitude, station.elevation, group.delta, group.azimuth);
    
        
        // Print them.
        // ttList.print(event.hypo.depth, group.delta);
        TTimeData travelTime = ttList.get(0);
        
        // Based on a tentative ID, just compute residuals and weights so 
        // that a robust estimate of the origin time correction can be 
        // made.  Without this step, the actual phase identification may 
        // not work correctly.  Note that only some of the first arrivals 
        // that are being used are considered and that the tentative ID is 
        // not remembered.
        if (group.delta <= 100d) {
          Pick pick = group.picks.get(0);
          boolean found;

          if (pick.used) {
            String phCode = pick.phCode;
            
            if (!phCode.substring(0,1).equals("PK") 
                && !phCode.substring(0,1).equals("P'") 
                && !phCode.substring(0,1).equals("Sc") 
                && !phCode.equals("Sg") && !phCode.equals("Sb") 
                && !phCode.equals("Sn") && !phCode.equals("Lg")) {
              if (pick.auto) {
                travelTime = ttList.get(0);
                
                if (!phCode.equals(travelTime.getPhCode())) {
                  badPs++;
                }

                pick.residual = pick.tt - travelTime.getTT();
                pick.weight = 1d / travelTime.getSpread();
                
                if (LocUtil.deBugLevel > 1 
                    && !phCode.equals(travelTime.getPhCode())) {
                  System.out.format("InitialPhaseID: %-8s -> %-8s auto\n", phCode, 
                      travelTime.getPhCode());
                }
              } else {
                found = false;
                
                for (int i = 0; i < ttList.size(); i++) {
                  travelTime = ttList.get(i);
                  
                  if (phCode.equals(travelTime.getPhCode())) {
                    // Note that this is slightly different from the Fortran 
                    // version where the weight is always from the first arrival.
                    pick.residual = pick.tt - travelTime.getTT();
                    pick.weight = 1d / travelTime.getSpread();
                    found = true;
                    break;
                  }
                }
                
                if (!found) {
                  travelTime = ttList.get(0);
                  pick.residual = pick.tt - travelTime.getTT();
                  pick.weight = 1d / travelTime.getSpread();
                  
                  if (LocUtil.deBugLevel > 1) { 
                    System.out.format("InitialPhaseID: "
                        + "%-8s -> %-8s human\n", phCode, travelTime.getPhCode());
                  }
                }
              }
              
              weightedResiduals.add(new Wresidual(pick, pick.residual, 
                  pick.weight, false, 0d, 0d, 0d));

              if (LocUtil.deBugLevel > 1) {
                System.out.format("InitialPhaseID push: %-5s %-8s %5.2f %7.4f %5.2f" 
                    + "%5.2f\n", pick.getStation().staID.staCode, pick.phCode, 
                    pick.residual, pick.weight, travelTime.getTT(), 
                    travelTime.getSpread());
              }
            }
          }
        }
      }
    }
    
    // Add in the Bayesian depth because the R-estimator code expects it.
    weightedResiduals.add(new Wresidual(null, hypo.getBayesianDepthResidual(), 
        hypo.getBayesianDepthWeight(), true, 0d, 0d, 0d));
    
    // Update the hypocenter origin time based on the residuals and weights 
    // pushed by the phaseID method.  Adjusting the origin time to something  
    // reasonable ensures that succeeding phase identifications have a chance.
    double median = rankSumEstimator.median();
    event.updateOriginTime(median);
    
    if (LocUtil.deBugLevel > 0) {
      System.out.format("\nUpdate origin: %f %f %f %d\n", hypo.getOriginTime(), 
          median, hypo.getOriginTime() + median, badPs);
    }
    
    // On a restart, reidentify all phases to be consistent with the new hypocenter.  
    // Note that we still needed the logic above to reset the origin time.
    if (event.getIsLocationRestarted()) {
      stepper.setEnviron();
      phaseID.phaseID(0.1d, 1d, true, true);
      event.computeStationStats();
      return;
    }
      
    if (LocUtil.deBugLevel > 1) {
      System.out.println();
    }

    // Based on the number of probably misidentified first arrivals:
    if (badPs < LocUtil.BADRATIO * event.getNumStationsUsed()) {
      // Just make the obvious re-identifications (i.e., autos).
      simplePhaseID();
    } else {
      // Re-identify any first arrivals that don't look right.
      complexPhaseID();
    }
  }
  
  /**
   * The simplePhaseID function is run if the initial phase identification seems to 
   * be making sense for the event (i.e., not too many misidentified first 
   * arrivals), we can go easy on the initial phase identification.
   */
  private void simplePhaseID() {
    // Loop over groups assessing automatic picks.
    for (int j = 0; j < event.getNumStations(); j++) {
      PickGroup group = event.getPickGroupList().get(j);
      
      if (group.picksUsed() > 0) {
        Pick pick = group.picks.get(0);
        
        // If the first arrival is automatic and not a crust or mantle P, don't 
        // use it.
        if (pick.auto && pick.used) {
          String phCode = pick.phCode;
          
          if (!phCode.equals("Pg") && !phCode.equals("Pb") 
              && !phCode.equals("Pn") && !phCode.equals("P")) {
            pick.used = false;
            
            if (LocUtil.deBugLevel > 1) {
              System.out.format("\tIdEasy: don't use %-5s %-8s\n", 
                  group.station.staID.staCode, pick.phCode);
            }
          }
        }
        
        // Don't use any secondary automatic phases.
        for (int i = 1; i < group.noPicks(); i++) {
          pick = group.picks.get(i);
          
          if (pick.auto && pick.used) {
            pick.used = false;
            
            if (LocUtil.deBugLevel > 1) {
              System.out.format("\tIdEasy: don't use %-5s %-8s\n", 
                  group.station.staID.staCode, pick.phCode);
            }
          }
        }
      }
    }
  }
  
  /**
   * The complexPhaseID function is run if the initial phase identification doesn't 
   * seem to be making sense for the event (i.e., too many misidentified first 
   * arrivals), we need to be stricter about the initial phase identification.
   */
  private void complexPhaseID() {
    Station station;
  
    // Loop over groups forcing automatic phases to conform.
    for (int j = 0; j < event.getNumStations(); j++) {
      PickGroup group = event.getPickGroupList().get(j);
      
      if (group.picksUsed() > 0) {
        Pick pick = group.picks.get(0);
        
        // If the first arrival is automatic and might be a misidentified first 
        // arrival,  force it to be the first theoretical arrival.
        if (pick.auto && pick.used) {
          String phCode = pick.phCode;
          
          if (group.delta <= 100d && !phCode.substring(0,1).equals("PK") 
              && !phCode.substring(0,1).equals("P'") 
              && !phCode.substring(0,1).equals("Sc") 
              && !phCode.equals("Sg") && !phCode.equals("Sb") 
              && !phCode.equals("Sn") && !phCode.equals("Lg")) {
            // For the first pick in the group, get the travel times.
            station = group.station;
            
            if (LocUtil.deBugLevel > 1) {
              System.out.println("" + station + ":");
            }

            // Do the travel-time calculation.
            TTime ttList = ttLocalSession.getTT(station.latitude, 
              station.longitude, station.elevation, group.delta, group.azimuth);
            
            // Print them.
            // ttList.print(event.hypo.depth, group.delta);
            
            // Set the phase code.  The travel time was already set in phaseID.
            pick.updateID(ttList.get(0).getPhCode());
            
            if (LocUtil.deBugLevel > 1) {
              System.out.format("\tIdHard: %-5s %-8s -> %-8s auto\n", 
                  group.station.staID.staCode, phCode, 
                  ttList.get(0).getPhCode());
            }
          } else {
            // If it's a core phase or not a common mis-identification, just 
            // don't use it.
            pick.used = false;
            
            if (LocUtil.deBugLevel > 1) { 
              System.out.format("\tIdHard: don't use %-5s %-8s\n", 
                  group.station.staID.staCode, pick.phCode);
            }
          }
        }
        
        // Don't use any secondary automatic phases.
        for (int i = 1; i < group.noPicks(); i++) {
          pick = group.picks.get(i);
          
          if (pick.auto && pick.used) {
            pick.used = false;
            
            if (LocUtil.deBugLevel > 1) {
              System.out.format("\tIdHard: don't use %-5s %-8s\n", 
                  group.station.staID.staCode, pick.phCode);
            }
          }
        }
      }
    }
  }
  
  /**
   * The resetUseFlags function puts some of phases that were temporarily taken 
   * out of the location back in after refining the starting location based on 
   * the initial phase identification and doing a more rigorous phase 
   * identification.
   */
  public void resetUseFlags() {
    // This simply resets no-used phases back to their initial input state.
    for (int j = 0; j < event.getNumStations(); j++) {
      PickGroup group = event.getPickGroupList().get(j);

      for (int i = 0; i < group.noPicks(); i++) {
        Pick pick = group.picks.get(i);
        if (!pick.used) {
          pick.used = pick.cmndUse;
        }
      }
    }
  }
  
  /**
   * The printInitialID function Lists the phases used in the initial 
   * relocation to the screen.  Note that they may have been re-identified after 
   * the initialID algorithm.
   */
  public void printInitialID() {
    System.out.println("\nInitial phase identification:");

    for (int j = 0; j < event.getNumStations(); j++) {
      PickGroup group = event.getPickGroupList().get(j);
      
      if (group.picksUsed() > 0) {
        Station station = group.station;
        
        for (int i = 0; i < group.noPicks(); i++) {
          Pick pick = group.picks.get(i);

          if (pick.used) {
            System.out.format("%-5s %-8s %6.1f %6.1f %3.0f %5.2f\n", 
                station.staID.staCode, pick.phCode, pick.residual, group.delta, 
                group.azimuth, pick.weight);
          }
        }
      }
    }
  }
}
