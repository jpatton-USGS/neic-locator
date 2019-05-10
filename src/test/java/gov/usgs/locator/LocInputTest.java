package gov.usgs.locator;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

import java.io.BufferedReader;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;
import java.util.Date;
import org.junit.Test;

/**
 * Testing class for the LocInput Class
 *
 * @author John Patton
 */
public class LocInputTest {
  public static double SOURCELATITUDE = 50.2075;
  public static double SOURCELONGITUDE = -114.8603;
  public static Date SOURCEORIGINTIME = new Date(1217617551880L);
  public static double SOURCEDEPTH = 509.98;
  public static boolean ISLOCATIONNEW = false;
  public static boolean ISLOCATIONHELD = false;
  public static boolean ISDEPTHHELD = false;
  public static boolean ISBAYESIANDEPTH = false;
  public static double BAYESIANDEPTH = 0.0;
  public static double BAYESIANSPREAD = 0.0;
  public static boolean USESVD = true;
  public static int NUMINPUTDATA = 26;

  /** This test is designed to test LocInput's ability to read a hydra input file. */
  @Test
  public void testFileRead() {

    // read the file
    BufferedReader inputReader = null;
    String inputString = "";
    try {
      inputReader = new BufferedReader(new FileReader("build/resources/test/hydraInput.txt"));
      String text = null;

      // each line is assumed to be part of the input
      while ((text = inputReader.readLine()) != null) {
        inputString += text;
      }
    } catch (FileNotFoundException e) {
      // no file
      fail();
    } catch (IOException e) {
      // problem reading
      fail();
    } finally {
      try {
        if (inputReader != null) {
          inputReader.close();
        }
      } catch (IOException e) {
        // can't close
        fail();
      }
    }

    // parse
    LocInput hydraIn = new LocInput();
    if (!hydraIn.readHydra(inputString)) {
      fail();
    }

    // check validity
    if (!hydraIn.isValid()) {
      fail();
    }

    // check input data
    assertEquals("Latitude:", SOURCELATITUDE, hydraIn.getSourceLatitude(), 0);

    assertEquals("Longitude: ", SOURCELONGITUDE, hydraIn.getSourceLongitude(), 0);

    assertEquals("Depth: ", SOURCEDEPTH, hydraIn.getSourceDepth(), 0);

    assertEquals("Time: ", SOURCEORIGINTIME, hydraIn.getSourceOriginTime());

    assertEquals("Is Location New: ", ISLOCATIONNEW, hydraIn.getIsLocationNew());

    assertEquals("Is Location Held: ", ISLOCATIONHELD, hydraIn.getIsLocationHeld());

    assertEquals("Is Depth Held: ", ISDEPTHHELD, hydraIn.getIsDepthHeld());

    assertEquals("Is Bayesian Depth: ", ISBAYESIANDEPTH, hydraIn.getIsBayesianDepth());

    assertEquals("Bayesan Depth: ", BAYESIANDEPTH, hydraIn.getBayesianDepth(), 0);

    assertEquals("Bayesian Spread: ", BAYESIANSPREAD, hydraIn.getBayesianSpread(), 0);

    assertEquals("Use SVD: ", USESVD, hydraIn.getUseSVD());

    assertEquals("Num Input Data: ", NUMINPUTDATA, hydraIn.getInputData().size());

    // not currently examining the phase list.*/
  }
}
