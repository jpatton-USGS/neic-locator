package gov.usgs.locator;

import gov.usgs.processingformats.LocationException;
import gov.usgs.processingformats.LocationRequest;
import gov.usgs.processingformats.LocationResult;
import gov.usgs.processingformats.Utility;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import org.json.simple.parser.ParseException;

/**
 * LocMain is the test driver class for the locator.
 *
 * @author John Patton
 *
 */
public class LocMain {
  /** 
   * A String containing the argument for specifying the model file path. 
   */
  public static final String MODELPATH_ARGUMENT = "--modelPath=";

  /** 
   * A String containing the argument for specifying the path to the input file.  
   */
  public static final String FILEPATH_ARGUMENT = "--filePath=";

  /** 
   * A String containing the argument for specifying the input file type.  
   */
  public static final String FILETYPE_ARGUMENT = "--fileType=";

  /** 
   * A String containing the argument for requesting the locator version.  
   */
  public static final String VERSION_ARGUMENT = "--version";

  /**
   * Main program for testing the locator.
   * 
   * @param args Command line arguments
   */
  public static void main(String[] args) {
    if (args == null || args.length == 0) {
      System.out.println("Usage: neic-locator" 
          + " --modelPath=[model path] --filePath=[file path] --fileType=[file type]");
      System.exit(1);    
    }

    // Default paths
    String modelPath = null;
    String filePath = null;
    String fileType = "hydra";

    // process arguments
    StringBuffer argumentList = new StringBuffer();
    for (String arg : args) {
      // save arguments for logging
      argumentList.append(arg).append(" ");
      
      if (arg.startsWith(MODELPATH_ARGUMENT)) {
        // get model path
        modelPath = arg.replace(MODELPATH_ARGUMENT, "");
      } else if (arg.startsWith(FILEPATH_ARGUMENT)) {
        // get file path
        filePath = arg.replace(FILEPATH_ARGUMENT, "");
      } else if (arg.startsWith(FILETYPE_ARGUMENT)) {
        // get file type
        fileType = arg.replace(FILETYPE_ARGUMENT, "");
      } else if (arg.equals(VERSION_ARGUMENT)) {
        // print version
        System.err.println("neic-locator");
        System.err.println("v0.1.0");
        System.exit(0);
      }
    }

    // print out args
    System.out.println("Command line arguments: " 
        + argumentList.toString().trim());

		// Set the debug level.
		LocUtil.deBugLevel = 1;

    // set up service
    LocService service = new LocService(modelPath);

    LocationRequest request = null;
    LocationResult result = null;
    if (fileType.equals("json")) {
      System.out.println("Reading a json file.");
      // read the file
      BufferedReader inputReader = null;
      String inputString = "";
      try {
        inputReader = new BufferedReader(
            new FileReader(filePath));
        String text = null;

        // each line is assumed to be part of the input
        while ((text = inputReader.readLine()) != null) {
          inputString += text;
        }
      } catch (FileNotFoundException e) {
        System.out.println("Exception: " + e.toString());
        System.exit(1);
      } catch (IOException e) {
        System.out.println("Exception: " + e.toString());
        System.exit(1);
      } finally {
        try {
          if (inputReader != null) {
            inputReader.close();
          }
        } catch (IOException e) {
          System.out.println("Exception: " + e.toString());
        }
      }

      // parse into request
      try {
        request = new LocationRequest(Utility.fromJSONString(inputString));

        String jsonString = Utility.toJSONString(request.toJSON());
        System.out.println("Input: \n" + jsonString);
      } catch (ParseException e) {
        System.out.println("Exception: " + e.toString());
        System.exit(1);
      }

      // check request
      if (request.isValid() == false) {
        ArrayList<String> errorList = request.getErrors();

        // combine the errors into a single string
        String errorString = new String();
        for (int i = 0; i < errorList.size(); i++) {
          errorString += " " + errorList.get(i);
        }

        System.out.println("Invalid request: " + errorString);
        System.exit(1);
      }

      // do location
      try {
        result = service.getLocation(request);
      } catch (LocationException e) {
        System.out.println("Exception: " + e.toString());
        System.exit(1);
      }

      // check result
      if (result.isValid() == false) {
        ArrayList<String> errorList = result.getErrors();

        // combine the errors into a single string
        String errorString = new String();
        for (int i = 0; i < errorList.size(); i++) {
          errorString += " " + errorList.get(i);
        }

        System.out.println("Invalid result: " + errorString);
      }      
    } else {
      System.out.println("Reading a hydra file.");
      LocInput hydraIn = new LocInput();
      LocOutput hydraOut = null;
      if (hydraIn.readHydra(filePath) == false) {
        System.exit(0);
      }
      
      String jsonString = Utility.toJSONString(hydraIn.toJSON());
      System.out.println("Input: " + jsonString);

      // check hydraIn
      if (hydraIn.isValid() == false) {
        ArrayList<String> errorList = hydraIn.getErrors();

        // combine the errors into a single string
        String errorString = new String();
        for (int i = 0; i < errorList.size(); i++) {
          errorString += " " + errorList.get(i);
        }

        System.out.println("Invalid hydraIn: " + errorString);
        System.exit(1);
      }

      // do location
      try {
        hydraOut = service.getLocation(hydraIn);
      } catch (LocationException e) {
        System.out.println("Exception: " + e.toString());
        System.exit(1);
      }

      // check hydraOut
      if (hydraOut.isValid() == false) {
        ArrayList<String> errorList = hydraOut.getErrors();

        // combine the errors into a single string
        String errorString = new String();
        for (int i = 0; i < errorList.size(); i++) {
          errorString += " " + errorList.get(i);
        }

        System.out.println("Invalid hydraOut: " + errorString);
      }

      System.out.println("Writing a hydra file.");
      hydraOut.writeHydra(filePath + ".out");
      result = (LocationData)hydraOut;
    }

    // print result
    if (result != null) {
      String jsonString = Utility.toJSONString(result.toJSON());
      System.out.println("Output: \n" + jsonString);
      System.exit(0);
    }

    // Exit.
    System.exit(1);
  }
}
