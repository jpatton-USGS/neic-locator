package gov.usgs.locator;

import gov.usgs.processingformats.LocationException;
import gov.usgs.processingformats.LocationRequest;
import gov.usgs.processingformats.LocationResult;
import gov.usgs.processingformats.Utility;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.logging.ConsoleHandler;
import java.util.logging.FileHandler;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogManager;
import java.util.logging.Logger;
import org.json.simple.parser.ParseException;

/**
 * Test driver for the locator.
 *
 * @author John Patton
 */
public class LocMain {
  /** A String containing the locator version */
  public static final String VERSION = "v0.1.0";

  /** A String containing the argument for specifying the model file path. */
  public static final String MODELPATH_ARGUMENT = "--modelPath=";

  /** A String containing the argument for specifying the input file path. */
  public static final String FILEPATH_ARGUMENT = "--filePath=";

  /** A String containing the argument for specifying the input file type. */
  public static final String INPUTTYPE_ARGUMENT = "--inputType=";

  /** A String containing the argument for specifying the output file type. */
  public static final String OUTPUTTYPE_ARGUMENT = "--outputType=";

  /** A String containing the argument for requesting the locator version. */
  public static final String VERSION_ARGUMENT = "--version";

  /** A String containing the argument for specifying a log file path. */
  public static final String LOGPATH_ARGUMENT = "--logPath=";

  /** A String containing the argument for specifying a log level. */
  public static final String LOGLEVEL_ARGUMENT = "--logLevel=";

  /** A String containing the argument for specifying the input directory. */
  public static final String INPUTDIR_ARGUMENT = "--inputDir=";

  /** A String containing the argument for specifying the output directory. */
  public static final String OUTPUTDIR_ARGUMENT = "--outputDir=";

  /** A String containing the argument for specifying the archive directory. */
  public static final String ARCHIVEDIR_ARGUMENT = "--archiveDir=";

  /** A String containing the argument for specifying the mode. */
  public static final String MODE_ARGUMENT = "--mode=";

  /** A String containing the argument for specifying to output a csv file. */
  public static final String CSVFILE_ARGUMENT = "--csvFile=";

  /** Mode to process one file (default) */
  public static final String MODE_SINGLE = "single";
  /** Mode to process batch */
  public static final String MODE_BATCH = "batch";
  /** Mode to run web service. */
  public static final String MODE_SERVICE = "service";

  /** Private logging object. */
  private static final Logger LOGGER = Logger.getLogger(LocMain.class.getName());

  /**
   * Main program for running the locator.
   *
   * @param args Command line arguments
   */
  public static void main(String[] args) {
    if (args == null || args.length == 0) {
      System.out.println(
          "Usage:\nneic-locator --modelPath=[model path] --inputType=[json or hydra] "
              + "\n\t--logPath=[log file path] --logLevel=[logging level] "
              + "\n\t--filePath=[input file path] --outputType=[optional type]"
              + "\nneic-locator --mode=batch --modelPath=[model path] "
              + "\n\t--inputType=[json or hydra] --logPath=[log file path] "
              + "\n\t--logLevel=[logging level] --inputDir=[input directory path] "
              + "\n\t--outputDir=[output directory path] "
              + "--archiveDir=[optional archive path] "
              + "\n\t--outputType=[optional type] "
              + "\n\t--csvFile=[optional csv file path]"
              + "\nneic-locator --mode=service");
      System.exit(1);
    }

    // Default paths
    String mode = MODE_SINGLE;
    String logPath = "./";
    String logLevel = "INFO";
    String modelPath = null;
    String filePath = null;
    String inputType = "hydra";
    String outputType = "hydra";
    String inputPath = "./input";
    String inputExtension = null;
    String outputPath = "./output";
    String outputExtension = null;
    String archivePath = null;
    String csvFile = null;

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
      } else if (arg.startsWith(INPUTTYPE_ARGUMENT)) {
        // get file type
        inputType = arg.replace(INPUTTYPE_ARGUMENT, "");
        outputType = inputType;
      } else if (arg.startsWith(OUTPUTTYPE_ARGUMENT)) {
        // get file type
        outputType = arg.replace(OUTPUTTYPE_ARGUMENT, "");
      } else if (arg.startsWith(LOGPATH_ARGUMENT)) {
        // get log path
        logPath = arg.replace(LOGPATH_ARGUMENT, "");
      } else if (arg.startsWith(LOGLEVEL_ARGUMENT)) {
        // get log level
        logLevel = arg.replace(LOGLEVEL_ARGUMENT, "");
      } else if (arg.equals(VERSION_ARGUMENT)) {
        // print version
        System.err.println("neic-locator");
        System.err.println(VERSION);
        System.exit(0);
      } else if (arg.startsWith(INPUTDIR_ARGUMENT)) {
        // get input path
        inputPath = arg.replace(INPUTDIR_ARGUMENT, "");
      } else if (arg.startsWith(OUTPUTDIR_ARGUMENT)) {
        // get output path
        outputPath = arg.replace(OUTPUTDIR_ARGUMENT, "");
      } else if (arg.startsWith(ARCHIVEDIR_ARGUMENT)) {
        // get archive path
        archivePath = arg.replace(ARCHIVEDIR_ARGUMENT, "");
      } else if (arg.startsWith(MODE_ARGUMENT)) {
        // get mode
        mode = arg.replace(MODE_ARGUMENT, "");
      } else if (arg.startsWith(CSVFILE_ARGUMENT)) {
        // get csv file
        csvFile = arg.replace(CSVFILE_ARGUMENT, "");
      }
    }

    if ("json".equals(inputType)) {
      inputExtension = ".locrequest";
    } else {
      inputExtension = ".txt";
    }

    if ("json".equals(outputType)) {
      outputExtension = ".locresult";
    } else {
      outputExtension = ".out";
    }

    LocMain locMain = new LocMain();

    // setup logging
    if (filePath != null) {
      locMain.setupLogging(logPath, getFileName(filePath) + ".log", logLevel);
    } else {
      locMain.setupLogging(logPath, getCurrentLocalDateTimeStamp() + "_locator.log", logLevel);
    }

    // print out version
    LOGGER.info("neic-locator " + VERSION);

    // log args
    LOGGER.fine("Command line arguments: " + argumentList.toString().trim());

    // log java and os information
    LOGGER.config("java.vendor = " + System.getProperty("java.vendor"));
    LOGGER.config("java.version = " + System.getProperty("java.version"));
    LOGGER.config("java.home = " + System.getProperty("java.home"));
    LOGGER.config("os.arch = " + System.getProperty("os.arch"));
    LOGGER.config("os.name = " + System.getProperty("os.name"));
    LOGGER.config("os.version = " + System.getProperty("os.version"));
    LOGGER.config("user.dir = " + System.getProperty("user.dir"));
    LOGGER.config("user.name = " + System.getProperty("user.name"));

    boolean locRC = false;

    if (MODE_SERVICE.equals(mode)) {
      gov.usgs.locatorservice.Application.main(args);
      // service runs in separate thread, just return from this method...
      return;
    } else if (MODE_BATCH.equals(mode)) {
      locRC =
          locMain.locateManyEvents(
              modelPath,
              inputPath,
              inputExtension,
              outputPath,
              outputExtension,
              archivePath,
              inputType,
              outputType,
              csvFile);
    } else {
      locRC =
          locMain.locateSingleEvent(
              modelPath, filePath, inputType, outputType, "./", outputExtension, csvFile);
    }

    // Exit.
    if (locRC) {
      System.exit(0);
    }
    System.exit(1);
  }

  /**
   * This function sets up logging for the locator.
   *
   * @param logPath A String containing the path to write log files to
   * @param logFile A String containing the name of the log file
   * @param logLevel A String holding the desired log level
   */
  public void setupLogging(String logPath, String logFile, String logLevel) {
    LogManager.getLogManager().reset();

    // parse the logging level
    Level level = getLogLevel(logLevel);

    LOGGER.config("Logging Level '" + level + "'");
    LOGGER.config("Log directory '" + logPath + "'");

    Logger rootLogger = Logger.getLogger("");
    rootLogger.setLevel(level);

    // create log directory, log file, and file handler
    try {
      File logDirectoryFile = new File(logPath);
      if (!logDirectoryFile.exists()) {
        LOGGER.fine("Creating log directory");
        if (!logDirectoryFile.mkdirs()) {
          LOGGER.warning("Unable to create log directory");
        }
      }

      FileHandler fileHandler = new FileHandler(logPath + "/" + logFile);
      fileHandler.setLevel(level);

      rootLogger.addHandler(fileHandler);
    } catch (Exception e) {
      LOGGER.log(Level.WARNING, "Unable to create log file handler", e);
    }

    // create console handler
    ConsoleHandler consoleHandler = new ConsoleHandler();
    consoleHandler.setLevel(level);
    rootLogger.addHandler(consoleHandler);

    // set all handlers to the same formatter
    for (Handler handler : rootLogger.getHandlers()) {
      handler.setFormatter(new SimpleLogFormatter());
    }
  }

  /**
   * This function converts a log level string into a logger level. This function converts a couple
   * of non-standard logging levels / abbreviations.
   *
   * @param logLevel A String holding the desired log level
   * @return A Level object containing the desired log level.
   */
  private Level getLogLevel(String logLevel) {
    if (logLevel == null) {
      return null;
    }
    try {
      return Level.parse(logLevel.toUpperCase());
    } catch (IllegalArgumentException e) {
      if (logLevel.equalsIgnoreCase("DEBUG")) {
        return Level.FINE;
      }
      if (logLevel.equalsIgnoreCase("WARN")) {
        return Level.WARNING;
      }
      throw new IllegalArgumentException(
          "Unresolved log level " + logLevel + " for java.util.logging", e);
    }
  }

  /**
   * This function extracts the file name from a given file path.
   *
   * @param filePath A String containing the full path to the file
   * @return A String containing the file name extracted from the full path.
   */
  public static String getFileName(String filePath) {
    // get the file name from input file path
    int start = filePath.lastIndexOf("/");
    if (start < 0) {
      start = filePath.lastIndexOf("\\");
    }
    if (start < 0) {
      start = 0;
    }

    int end = filePath.lastIndexOf(".");
    if (end <= 0) {
      end = filePath.length();
    }

    return filePath.substring(start, end);
  }

  /**
   * This function returns the current local time as a string
   *
   * @return A String containing current local time formatted in the form "yyyyMMdd_HHmmss".
   */
  public static String getCurrentLocalDateTimeStamp() {
    return LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
  }

  /**
   * This function locates a single event.
   *
   * @param modelPath A String containing the path to the required model files
   * @param filePath A String containing the full path to the locator input file
   * @param inputType A String containing the type of the locator input file
   * @param outputType A String containing the type of the locator output file
   * @param outputPath A String containing the path to write the results
   * @param outputExtension A String containing the extension to use for output files
   * @param csvFile An optional String containing full path to the csv formatted file, null to
   *     disable
   * @return A boolean flag indicating whether the locaton was successful
   */
  public boolean locateSingleEvent(
      String modelPath,
      String filePath,
      String inputType,
      String outputType,
      String outputPath,
      String outputExtension,
      String csvFile) {

    // read the file
    BufferedReader inputReader = null;
    String inputString = "";
    try {
      inputReader = new BufferedReader(new FileReader(filePath));
      String text = null;

      // each line is assumed to be part of the input
      while ((text = inputReader.readLine()) != null) {
        inputString += text;
      }
    } catch (FileNotFoundException e) {
      // no file
      LOGGER.severe("Exception: " + e.toString());
      return false;
    } catch (IOException e) {
      // problem reading
      LOGGER.severe("Exception: " + e.toString());
      return false;
    } finally {
      try {
        if (inputReader != null) {
          inputReader.close();
        }
      } catch (IOException e) {
        // can't close
        LOGGER.severe("Exception: " + e.toString());
      }
    }

    // parse the file
    LocationRequest request = null;
    if ("json".equals(inputType)) {
      LOGGER.fine("Parsing a json file.");

      // parse into request
      try {
        request = new LocationRequest(Utility.fromJSONString(inputString));
      } catch (ParseException e) {
        // parse failure
        LOGGER.severe("Exception: " + e.toString());
        return false;
      }
    } else {
      LOGGER.fine("Parsing a hydra file.");

      // Use LocInput to get access to read routine
      LocInput hydraIn = new LocInput();
      if (!hydraIn.readHydra(inputString)) {
        return false;
      }

      request = (LocationRequest) hydraIn;
    }

    // do location
    LocationResult result = null;
    if (request != null) {
      try {
        // set up service
        LocService service = new LocService(modelPath);
        result = service.getLocation(request);
      } catch (LocationException e) {
        LOGGER.severe("Exception: " + e.toString());
        return false;
      }
    }

    // Write the result to disk
    if (result != null) {
      // create the output file name
      String outFileName =
          outputPath + File.separatorChar + getFileName(filePath) + outputExtension;
      LocOutput locOut = (LocOutput) result;

      if ("json".equals(outputType)) {
        locOut.writeJSON(outFileName);
      } else {
        locOut.writeHydra(outFileName);
      }

      // append csv to file
      if (csvFile != null) {
        try {
          FileWriter fileWriter = new FileWriter(csvFile, true); // Set true for append mode
          PrintWriter printWriter = new PrintWriter(fileWriter);
          printWriter.println(result.toCSV());
          printWriter.close();
        } catch (Exception e) {
          LOGGER.severe(e.toString());
        }
      }

      // success
      return true;
    }

    // Exit.
    return false;
  }

  /**
   * This function locates all events in a given input directory
   *
   * @param modelPath A String containing the path to the required model files
   * @param inputPath A String containing the full path to directory containing input files
   * @param inputExtension A String containing the extension of locator input files
   * @param outputPath A String containing the path to write the results
   * @param outputExtension A String containing the extension to use for output files
   * @param archivePath An optional String containing the full path to directory to archive input
   *     files, null to disable, if disabled, input files are deleted
   * @param inputType A String containing the type of the locator input file
   * @param outputType A String containing the type of the locator output file
   * @param csvFile An optional String containing full path to the csv formatted file, null to
   *     disable
   * @return A boolean flag indicating whether the locatons were successful
   */
  public boolean locateManyEvents(
      String modelPath,
      String inputPath,
      String inputExtension,
      String outputPath,
      String outputExtension,
      String archivePath,
      String inputType,
      String outputType,
      String csvFile) {

    // create the output and archive paths if they don't
    // already exist
    if (outputPath != null) {
      File outputDir = new File(outputPath);
      if (!outputDir.exists()) {
        outputDir.mkdirs();
      }
    } else {
      LOGGER.severe("Output Path is not specified, exitting.");
      return false;
    }

    if (archivePath != null) {
      File archiveDir = new File(archivePath);
      if (!archiveDir.exists()) {
        archiveDir.mkdirs();
      }
    }

    // setup the directories
    File inputDir = new File(inputPath);
    if (!inputDir.exists()) {
      LOGGER.severe("Input Path is not valid, exitting.");
      return false;
    }

    // for all the files currently in the input directory
    for (File inputFile : inputDir.listFiles()) {
      // if the file has the right extension
      if (inputFile.getName().endsWith((inputExtension))) {
        // read the file
        // String fileName = inputFile.getName();
        String filePath = inputFile.getAbsolutePath();

        if (locateSingleEvent(
            modelPath, filePath, inputType, outputType, outputPath, outputExtension, csvFile)) {
          // done with the file
          if (archivePath == null) {
            // not archiving, just delete it
            inputFile.delete();
          } else {
            // Move file to archive directory
            inputFile.renameTo(
                new File(
                    archivePath + File.separatorChar + getFileName(filePath) + inputExtension));
          }
        } else {
          // we had an error, rename file as errored so we don't retry the same file
          inputFile.renameTo(new File(filePath + ".error"));
        }
      }
    }

    // done
    return true;
  }
}
