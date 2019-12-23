package com.turning_leaf_technologies.carlx;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.Date;

import com.turning_leaf_technologies.config.ConfigUtil;
import com.turning_leaf_technologies.grouping.BaseMarcRecordGrouper;
import com.turning_leaf_technologies.grouping.MarcRecordGrouper;
import com.turning_leaf_technologies.grouping.RemoveRecordFromWorkResult;
import com.turning_leaf_technologies.indexing.IlsExtractLogEntry;
import com.turning_leaf_technologies.indexing.IndexingProfile;
import com.turning_leaf_technologies.indexing.RecordIdentifier;
import com.turning_leaf_technologies.logging.LoggingUtil;
import com.turning_leaf_technologies.net.NetworkUtils;
import com.turning_leaf_technologies.net.WebServiceResponse;
import com.turning_leaf_technologies.reindexer.GroupedWorkIndexer;
import org.apache.logging.log4j.Logger;
import org.ini4j.Ini;
import org.marc4j.*;
import org.marc4j.marc.DataField;
import org.marc4j.marc.Record;
import org.marc4j.marc.Subfield;
import org.marc4j.marc.VariableField;
import org.marc4j.marc.impl.DataFieldImpl;
import org.marc4j.marc.impl.MarcFactoryImpl;
import org.marc4j.marc.impl.SubfieldImpl;
import org.w3c.dom.Document;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;

public class CarlXExportMain {
	private static Logger logger;
	private static IndexingProfile indexingProfile;

	private static String marcOutURL;
	private static Ini configIni;
	private static Connection dbConn;
	private static String serverName;
	private static MarcRecordGrouper recordGroupingProcessorSingleton;
	private static GroupedWorkIndexer groupedWorkIndexer;

	private static IlsExtractLogEntry logEntry;

	private static boolean hadErrors = false;
	private static long startTimeForLogging;

	public static void main(String[] args) {
		if (args.length == 0) {
			System.out.println("You must provide the server name as the first argument.");
			System.exit(1);
		}
		serverName = args[0];

		String profileToLoad = "carlx";
		if (args.length > 1){
			profileToLoad = args[1];
		}

		String processName = "carlx_export";
		logger = LoggingUtil.setupLogging(serverName, processName);

		while (true){
			Date startTime = new Date();
			startTimeForLogging = startTime.getTime() / 1000;
			logger.info(startTime.toString() + ": Starting CarlX Extract");

			// Read the base INI file to get information about the server (current directory/cron/config.ini)
			configIni = ConfigUtil.loadConfigFile("config.ini", serverName, logger);

			int numChanges = 0;

			try{
				//Connect to the Aspen Database
				String databaseConnectionInfo = ConfigUtil.cleanIniValue(configIni.get("Database", "database_aspen_jdbc"));
				if (databaseConnectionInfo == null) {
					logger.error("Please provide database_aspen_jdbc within config.pwd.ini");
					System.exit(1);
				}
				dbConn = DriverManager.getConnection(databaseConnectionInfo);
				if (dbConn == null) {
					logger.error("Could not establish connection to database at " + databaseConnectionInfo);
					System.exit(1);
				}

				logEntry = new IlsExtractLogEntry(dbConn, profileToLoad, logger);
				//Remove log entries older than 45 days
				long earliestLogToKeep = (startTime.getTime() / 1000) - (60 * 60 * 24 * 45);
				try {
					int numDeletions = dbConn.prepareStatement("DELETE from ils_extract_log WHERE startTime < " + earliestLogToKeep + " AND indexingProfile = '" + profileToLoad + "'").executeUpdate();
					logger.info("Deleted " + numDeletions + " old log entries");
				} catch (SQLException e) {
					logger.error("Error deleting old log entries", e);
				}

				// Connect to the CARL.X database and get information about API
				CarlXInstanceInformation carlXInstanceInformation = initializeCarlXConnection();
				if (carlXInstanceInformation == null){
					logEntry.incErrors();
					logEntry.addNote("Could not connect to the CARL.X database");
					logEntry.setFinished();
					continue;
				}else{
					profileToLoad = carlXInstanceInformation.indexingProfileName;
				}

				indexingProfile = IndexingProfile.loadIndexingProfile(dbConn, profileToLoad, logger);
				if (indexingProfile.isRunFullUpdate()){
					//Un mark that a full update needs to be done
					PreparedStatement updateSettingsStmt = dbConn.prepareStatement("UPDATE indexing_profiles set runFullUpdate = 0 where id = ?");
					updateSettingsStmt.setLong(1, indexingProfile.getId());
					updateSettingsStmt.executeUpdate();
				}

				numChanges = updateRecords(dbConn, carlXInstanceInformation);

				logger.info("Finished export of bibs and items, starting export of holds");

				//TODO: Are we keeping the CARL.X database connection open too long?
				if (carlXInstanceInformation.carlXConn != null){
					try{
						exportHolds(carlXInstanceInformation.carlXConn, dbConn);
					}catch(Exception e){
						logger.error("Error exporting holds", e);
						System.out.println("Error: " + e.toString());
						e.printStackTrace();
					}
				}else{
					logger.warn("Did not export holds because connection to the CARL.X database was not established");
					logEntry.addNote("Did not export holds because connection to the CARL.X database was not established");
					logEntry.incErrors();
				}

				logEntry.setFinished();

				try{
					//Close the connection
					dbConn.close();

					if (carlXInstanceInformation.carlXConn != null){
						carlXInstanceInformation.carlXConn.close();
					}

					if (groupedWorkIndexer != null) {
						groupedWorkIndexer.finishIndexingFromExtract();
						recordGroupingProcessorSingleton = null;
						groupedWorkIndexer = null;
					}
				}catch(Exception e){
					System.out.println("Error closing connection: " + e.toString());
					logger.error("Error closing connection: ", e);
				}
			}catch (Exception e){
				logger.error("Error connecting to database ", e);
			}

			//Pause before running the next export (longer if we didn't get any actual changes)
			try {
				if (numChanges == 0 || logEntry.hasErrors()) {
					Thread.sleep(1000 * 60 * 5);
				}else {
					Thread.sleep(1000 * 60);
				}
			} catch (InterruptedException e) {
				logger.info("Thread was interrupted");
			}
		} //Infinite loop
	}
	private static int updateRecords(Connection dbConn, CarlXInstanceInformation carlXInstanceInformation){
		//Check to see if we need to update from the MARC export
		File marcExportPath = new File(indexingProfile.getMarcPath());
		File[] exportedMarcFiles = marcExportPath.listFiles((dir, name) -> name.endsWith("mrc") || name.endsWith("marc"));
		long latestMarcFile = 0;
		if (exportedMarcFiles != null && exportedMarcFiles.length > 0){
			for (File exportedMarcFile : exportedMarcFiles) {
				if (exportedMarcFile.lastModified() > latestMarcFile){
					latestMarcFile = exportedMarcFile.lastModified();
				}
			}
		}

		if (exportedMarcFiles != null && exportedMarcFiles.length > 0 && (indexingProfile.getLastUpdateFromMarcExport() == 0 || (latestMarcFile / 1000) > indexingProfile.getLastUpdateFromMarcExport())){
			//Do not load the MARC file if it was updated in the last 5 minutes to be sure it is fully written
			if (startTimeForLogging - (latestMarcFile / 1000) < 300){
				return 0;
			}
			//Update all records based on the MARC export
			logEntry.addNote("Updating based on MARC extract");
			return updateRecordsUsingMarcExtract(exportedMarcFiles, dbConn, latestMarcFile / 1000);
		}else{
			//Get updates from the API
			logEntry.addNote("Updating based on API");
			return updateRecordsUsingAPI(dbConn, carlXInstanceInformation);
		}
	}

	/**
	 * Updates Aspen using the MARC export or exports provided.
	 * To see which records are deleted it needs to get a list of all records that are already in the database
	 * so it can detect what has been deleted.
	 *
	 * @param exportedMarcFiles - An array of files to process
	 * @param dbConn			- Connection to the Aspen database
	 * @param latestMarcExport  - Timestamp of the latest MARC export
	 * @return - total number of changes that were found
	 */
	private static int updateRecordsUsingMarcExtract(File[] exportedMarcFiles, Connection dbConn, Long latestMarcExport) {
		int totalChanges = 0;
		MarcRecordGrouper recordGroupingProcessor = getRecordGroupingProcessor(dbConn);
		if (!recordGroupingProcessor.isValid()){
			logEntry.addNote("Record Grouping Processor was not valid");
			logEntry.incErrors();
			return totalChanges;
		}else if (!recordGroupingProcessor.loadExistingTitles(logEntry)){
			return totalChanges;
		}

		for (File curBibFile : exportedMarcFiles) {
			int numRecordsRead = 0;
			String lastRecordProcessed = "";
			try {
				FileInputStream marcFileStream = new FileInputStream(curBibFile);
				MarcReader catalogReader = new MarcPermissiveStreamReader(marcFileStream, true, true, indexingProfile.getMarcEncoding());
				while (catalogReader.hasNext()) {
					logEntry.incProducts();
					try{
						Record curBib = catalogReader.next();
						RecordIdentifier recordIdentifier = recordGroupingProcessor.getPrimaryIdentifierFromMarcRecord(curBib, indexingProfile.getName(), indexingProfile.isDoAutomaticEcontentSuppression());
						boolean deleteRecord = false;
						if (recordIdentifier == null) {
							//logger.debug("Record with control number " + curBib.getControlNumber() + " was suppressed or is eContent");
							String controlNumber = curBib.getControlNumber();
							if (controlNumber == null) {
								logger.warn("Bib did not have control number or identifier");
							}
						}else if (!recordIdentifier.isSuppressed()) {
							String recordNumber = recordIdentifier.getIdentifier();

							BaseMarcRecordGrouper.MarcStatus marcStatus = recordGroupingProcessor.writeIndividualMarc(indexingProfile, curBib, recordNumber, logger);
							if (marcStatus != BaseMarcRecordGrouper.MarcStatus.UNCHANGED || indexingProfile.isRunFullUpdate()) {
								String permanentId = recordGroupingProcessor.processMarcRecord(curBib, marcStatus != BaseMarcRecordGrouper.MarcStatus.UNCHANGED);
								if (permanentId == null){
									//Delete the record since it is suppressed
									deleteRecord = true;
								}else {
									if (marcStatus == BaseMarcRecordGrouper.MarcStatus.NEW){
										logEntry.incAdded();
									}else {
										logEntry.incUpdated();
									}
									getGroupedWorkIndexer(dbConn).processGroupedWork(permanentId);
									totalChanges++;
								}
							}else{
								logEntry.incSkipped();
							}
							//Mark that the record was processed
							recordGroupingProcessor.removeExistingRecord(recordIdentifier.getIdentifier());
							lastRecordProcessed = recordNumber;
						}else{
							//Delete the record since it is suppressed
							deleteRecord = true;
						}
						if (deleteRecord){
							RemoveRecordFromWorkResult result = recordGroupingProcessor.removeRecordFromGroupedWork(indexingProfile.getName(), recordIdentifier.getIdentifier());
							if (result.reindexWork){
								getGroupedWorkIndexer(dbConn).processGroupedWork(result.permanentId);
							}else if (result.deleteWork){
								//Delete the work from solr and the database
								getGroupedWorkIndexer(dbConn).deleteRecord(result.permanentId, result.groupedWorkId);
							}
							logEntry.incDeleted();
							totalChanges++;
						}
					}catch (MarcException me){
						logger.error("Error processing individual record  on record " + numRecordsRead + " of " + curBibFile.getAbsolutePath() + " the last record processed was " + lastRecordProcessed + " trying to continue", me);
						logEntry.addNote("Error processing individual record  on record " + numRecordsRead + " of " + curBibFile.getAbsolutePath() + " the last record processed was " + lastRecordProcessed + " trying to continue");
						logEntry.incErrors();
					}
					numRecordsRead++;
					if (numRecordsRead % 250 == 0) {
						logEntry.saveResults();
					}
				}
				marcFileStream.close();
			} catch (Exception e) {
				logger.error("Error loading CARL.X bibs on record " + numRecordsRead + " in profile " + indexingProfile.getName() + " the last record processed was " + lastRecordProcessed + " file " + curBibFile.getAbsolutePath(), e);
				logEntry.addNote("Error loading CARL.X bibs on record " + numRecordsRead + " in profile " + indexingProfile.getName() + " the last record processed was " + lastRecordProcessed + " file " + curBibFile.getAbsolutePath());
				logEntry.incErrors();
			}
		}

		//TODO: Loop through remaining records and delete them


		try {
			PreparedStatement updateMarcExportStmt = dbConn.prepareStatement("UPDATE indexing_profiles set lastUpdateFromMarcExport = ? where id = ?");
			updateMarcExportStmt.setLong(1, latestMarcExport);
			updateMarcExportStmt.setLong(2, indexingProfile.getId());
			updateMarcExportStmt.executeUpdate();
		}catch (Exception e){
			logger.error("Error updating lastUpdateFromMarcExport", e);
			logEntry.addNote("Error updating lastUpdateFromMarcExport");
		}

		return totalChanges;
	}

	private static int updateRecordsUsingAPI(Connection dbConn, CarlXInstanceInformation carlXInstanceInformation){
		int totalChanges = 0;
		// Get MarcOut WSDL url for SOAP calls
		marcOutURL = carlXInstanceInformation.baseAPIUrl + "/CarlXAPI/MarcoutAPI.wsdl";
		try{
			logger.warn("Starting export of bibs and items from CARL.X");

			long lastCarlXExtractTime = indexingProfile.getLastUpdateOfChangedRecords();
			long lastUpdateFromMarc = indexingProfile.getLastUpdateFromMarcExport();
			if (lastUpdateFromMarc > lastCarlXExtractTime){
				//get an extra hour since it can take awhile for the MARC export to complete.
				lastCarlXExtractTime = lastUpdateFromMarc - 60 * 60;
			}else {
				if (lastCarlXExtractTime == 0) {
					lastCarlXExtractTime = new Date().getTime() / 1000 - 24 * 60 * 60;
				}
			}

			Timestamp lastExtractTimestamp = new Timestamp(lastCarlXExtractTime * 1000);

			//Get a list of bibs that have changed.  CARL.X does not have a way to load all BIBs in the system
			//so if we are running a full update we will just load everything that has changed since January 1, 2000
			//which is before CARL.X came into existence.
			DateFormat timeFormat = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'");
			timeFormat.setTimeZone(TimeZone.getTimeZone("UTC"));
			String beginTimeString = timeFormat.format(lastExtractTimestamp);
			logEntry.addNote("Updating changes since " + beginTimeString);

			ArrayList<String> updatedBibs = new ArrayList<>();
			ArrayList<String> createdBibs = new ArrayList<>();
			ArrayList<String> deletedBibs = new ArrayList<>();
			ArrayList<String> updatedItemIDs = new ArrayList<>();
			ArrayList<String> createdItemIDs = new ArrayList<>();
			ArrayList<String> deletedItemIDs = new ArrayList<>();
			ArrayList<ItemChangeInfo> itemUpdates;
			ArrayList<ItemChangeInfo> createdItems = new ArrayList<>();
			ArrayList<ItemChangeInfo> deletedItems;

			if (!getChangedBibsFromCarlXApi(beginTimeString, updatedBibs, createdBibs, deletedBibs)) {
				//Halt execution
				logger.error("Failed to getChangedBibsFromCarlXApi, exiting");
				logEntry.incErrors();
				logEntry.addNote("Failed to getChangedBibsFromCarlXApi, exiting");
				return totalChanges;
			}
			logger.info("Loaded updated bibs");

			//Load updated items, we don't need to do this if we are running a full update
			logger.debug("Calling GetChangedItemsRequest with BeginTime of " + beginTimeString);
			if (!getChangedItemsFromCarlXApi(beginTimeString, updatedItemIDs, createdItemIDs, deletedItemIDs)) {
				//Halt execution
				logger.error("Failed to getChangedItemsFromCarlXApi, exiting");
				logEntry.incErrors();
				logEntry.addNote("Failed to getChangedItemsFromCarlXApi, exiting");
				return totalChanges;
			} else {
				logger.info("Loaded updated items");
			}

			// Fetch Item Information for each ID.  What we really want is a full list of BIDs
			// so we can fetch MARC records for them.
			itemUpdates = fetchItemInformation(updatedItemIDs);
			if (hadErrors) {
				logger.error("Failed to Fetch Item Information for updated items");
				logEntry.incErrors();
				logEntry.addNote("Failed to Fetch Item Information for updated items");
				return totalChanges;
			} else {
				logger.info("Fetched Item information for updated items");
				for (ItemChangeInfo itemUpdate : itemUpdates){
					if (!createdBibs.contains(itemUpdate.getBID()) && !updatedBibs.contains(itemUpdate.getBID())){
						updatedBibs.add(itemUpdate.getBID());
					}
				}
			}

			if (createdItemIDs.size() > 0) {
				createdItems = fetchItemInformation(createdItemIDs);
				if (hadErrors) {
					logger.error("Failed to Fetch Item Information for created items");
					logEntry.incErrors();
					logEntry.addNote("Failed to Fetch Item Information for created items");
					return totalChanges;
				} else {
					logger.info("Fetched Item information for created items");
					for (ItemChangeInfo itemUpdate : createdItems) {
						if (!createdBibs.contains(itemUpdate.getBID()) && !updatedBibs.contains(itemUpdate.getBID())) {
							updatedBibs.add(itemUpdate.getBID());
						}
					}
				}
			}

			if (deletedItemIDs.size() > 0) {
				deletedItems = fetchItemInformation(deletedItemIDs);
				if (hadErrors) {
					logger.error("Failed to Fetch Item Information for deleted items");
					//logEntry.incErrors();
					logEntry.addNote("Failed to Fetch Item Information for deleted items");
					//return totalChanges;
				} else {
					logger.info("Fetched Item information for deleted items");
					for (ItemChangeInfo itemUpdate : deletedItems) {
						if (!deletedBibs.contains(itemUpdate.getBID()) && !updatedBibs.contains(itemUpdate.getBID())) {
							updatedBibs.add(itemUpdate.getBID());
						}
					}
				}
			}

			//Update total products to be processed
			logEntry.setNumProducts(updatedBibs.size() + createdBibs.size() + deletedBibs.size());

			// Update Changed Bibs
			totalChanges = updateBibRecords(updatedBibs, updatedItemIDs, createdItemIDs, deletedItemIDs, itemUpdates, createdItems, false);
			logger.debug("Done updating Bib Records");
			logEntry.saveResults();
			//This should not be needed since we add anything where just the item was added above
//			totalChanges += updateChangedItems(createdItemIDs, deletedItemIDs, itemUpdates, createdItems);
//			logger.debug("Done updating Item Records");

			// Now remove Any left-over deleted items.  The APIs give us the item id, but not the bib id.  We may need to
			// look them up within Solr as long as the item id is exported as part of the MARC record
			if (deletedItemIDs.size() > 0) {
				for (String deletedItemID : deletedItemIDs) {
					logger.debug("Item " + deletedItemID + " should be deleted, but we didn't get a bib for it.");
					//TODO: Now you *really* have to get the BID, dude.
				}
			}

			//Process Deleted Bibs
			if (deletedBibs.size() > 0) {
				logger.debug("There are " + deletedBibs.size() + " that still need to be processed.");
				for (String deletedBibID : deletedBibs) {
					RemoveRecordFromWorkResult result = recordGroupingProcessorSingleton.removeRecordFromGroupedWork(indexingProfile.getName(), deletedBibID);
					if (result.reindexWork){
						getGroupedWorkIndexer(dbConn).processGroupedWork(result.permanentId);
					}else if (result.deleteWork){
						//Delete the work from solr and the database
						getGroupedWorkIndexer(dbConn).deleteRecord(result.permanentId, result.groupedWorkId);
					}
					logEntry.incDeleted();
					totalChanges++;
				}
			}
			logEntry.saveResults();

			//Process New Bibs
			if (createdBibs.size() > 0) {
				logger.debug("There are " + createdBibs.size() + " that need to be processed");
				totalChanges += updateBibRecords(createdBibs, updatedItemIDs, createdItemIDs, deletedItemIDs, itemUpdates, createdItems, true);
			}
			logEntry.saveResults();

			if (indexingProfile.isRunFullUpdate()) {
				PreparedStatement updateVariableStmt = dbConn.prepareStatement("UPDATE indexing_profiles set lastUpdateOfAllRecords = ?, runFullUpdate = 0 WHERE id = ?");
				updateVariableStmt.setLong(1, startTimeForLogging);
				updateVariableStmt.setLong(2, indexingProfile.getId());
				updateVariableStmt.executeUpdate();
				updateVariableStmt.close();
			} else {
				if (!logEntry.hasErrors()) {
					PreparedStatement updateVariableStmt = dbConn.prepareStatement("UPDATE indexing_profiles set lastUpdateOfChangedRecords = ? WHERE id = ?");
					updateVariableStmt.setLong(1, startTimeForLogging);
					updateVariableStmt.setLong(2, indexingProfile.getId());
					updateVariableStmt.executeUpdate();
					updateVariableStmt.close();
				}
			}
		} catch (Exception e){
			logger.error("Error loading changed records from CARL.X", e);
			logEntry.incErrors();
			//Don't quit since that keeps the exporter from running continuously
		}

		return totalChanges;
	}

	private static CarlXInstanceInformation initializeCarlXConnection() throws SQLException {
		//Get information about the account profile for Carl.X
		PreparedStatement accountProfileStmt = dbConn.prepareStatement("SELECT * from account_profiles WHERE ils = 'carlx'");
		ResultSet accountProfileRS = accountProfileStmt.executeQuery();
		CarlXInstanceInformation carlXInstanceInformation = null;
		if (accountProfileRS.next()) {
			String host = accountProfileRS.getString("databaseHost");
			String port = accountProfileRS.getString("databasePort");
			if (port == null || port.length() == 0) {
				port = "1521";
			}
			String databaseName = accountProfileRS.getString("databaseName");
			String user = accountProfileRS.getString("databaseUser");
			String password = accountProfileRS.getString("databasePassword");
			String databaseJdbcUrl = "jdbc:oracle:thin:@//" + host + ":" + port +"/" + databaseName;
			carlXInstanceInformation = new CarlXInstanceInformation();
			carlXInstanceInformation.indexingProfileName = accountProfileRS.getString("recordSource");
			carlXInstanceInformation.baseAPIUrl = accountProfileRS.getString("patronApiUrl");

			Connection carlxConn;
			try{
				//Open the connection to the database
				Properties props = new Properties();
				props.setProperty("user", user);
				props.setProperty("password", password);
				carlxConn = DriverManager.getConnection(databaseJdbcUrl, props);

				carlXInstanceInformation.carlXConn = carlxConn;
			}catch(Exception e){
				logger.error("Error connecting to CARL.X database", e);
			}
		} else {
			logger.error("Could not find an account profile for CARL.X stopping");
			System.exit(1);
		}
		return carlXInstanceInformation;
	}

	private static int updateBibRecords(ArrayList<String> updatedBibs, ArrayList<String> updatedItemIDs, ArrayList<String> createdItemIDs, ArrayList<String> deletedItemIDs, ArrayList<ItemChangeInfo> itemUpdates, ArrayList<ItemChangeInfo> createdItems, boolean isNew) {
		// Fetch new Marc Data
		// Note: There is an Include949ItemData flag, but it hasn't been implemented by TLC yet. plb 9-15-2016
		// Build Marc Fetching Soap Request
		if (updatedBibs.size() > 100){
			logger.warn("There are more than 100 bibs that need updates " + updatedBibs.size());
		}
		int numUpdates = 0;
		while (updatedBibs.size() > 0) {
			logger.debug("Getting data for " + updatedBibs.size() + " updated bibs");
			try {
				String getMarcRecordsSoapRequestStart = "<soapenv:Envelope xmlns:soapenv=\"http://schemas.xmlsoap.org/soap/envelope/\" xmlns:mar=\"http://tlcdelivers.com/cx/schemas/marcoutAPI\" xmlns:req=\"http://tlcdelivers.com/cx/schemas/request\">\n" +
						"<soapenv:Header/>\n" +
						"<soapenv:Body>\n" +
						"<mar:GetMARCRecordsRequest>\n";
				String getMarcRecordsSoapRequestEnd = "<mar:Include949ItemData>0</mar:Include949ItemData>\n" +
						"<mar:IncludeOnlyUnsuppressed>0</mar:IncludeOnlyUnsuppressed>\n" +
						"<mar:Modifiers>\n" +
						"</mar:Modifiers>\n" +
						"</mar:GetMARCRecordsRequest>\n" +
						"</soapenv:Body>\n" +
						"</soapenv:Envelope>";

				StringBuilder getMarcRecordsSoapRequest = new StringBuilder(getMarcRecordsSoapRequestStart);
				// Updated Bibs
				@SuppressWarnings("unchecked")
				ArrayList<String> updatedBibCopy = (ArrayList<String>)updatedBibs.clone();
				int numAdded = 0;
				for (String updatedBibID : updatedBibCopy) {
					if (updatedBibID.length() > 0) {
						getMarcRecordsSoapRequest.append("<mar:BID>").append(updatedBibID).append("</mar:BID>\n");
						numAdded++;
					}
					updatedBibs.remove(updatedBibID);
					if (numAdded >= 100){
						break;
					}
				}
				getMarcRecordsSoapRequest.append(getMarcRecordsSoapRequestEnd);

				//logger.debug("Getting MARC record details " + getMarcRecordsSoapRequest);
				WebServiceResponse marcRecordSOAPResponse = NetworkUtils.postToURL(marcOutURL, getMarcRecordsSoapRequest.toString(), "text/xml", null, logger);
				if (marcRecordSOAPResponse.isSuccess()) {
					// Parse Response
					Document doc = createXMLDocumentForSoapResponse(marcRecordSOAPResponse);
					logger.debug("MARC record response " + doc.toString());
					Node soapEnvelopeNode = doc.getFirstChild();
					Node soapBodyNode = soapEnvelopeNode.getLastChild();
					Node getMarcRecordsResponseNode = soapBodyNode.getFirstChild();
					NodeList marcRecordInfo = getMarcRecordsResponseNode.getChildNodes();
					Node marcRecordsResponseStatus = getMarcRecordsResponseNode.getFirstChild().getFirstChild();
					String responseStatusCode = marcRecordsResponseStatus.getFirstChild().getTextContent();

					if (responseStatusCode.equals("0")) { // Successful response
						int l = marcRecordInfo.getLength();
						for (int i = 1; i < l; i++) { // (skip first node because it is the response status)
							try {
								String currentBibID = updatedBibCopy.get(i - 1);
								String currentFullBibID = getFileIdForRecordNumber(currentBibID);
								//logger.debug("Updating " + currentFullBibID);
								//logger.debug("Response from CARL.X\r\n" + marcRecordSOAPResponse.getMessage());
								Node marcRecordNode = marcRecordInfo.item(i);

								// Build Marc Object from the API data
								Record updatedMarcRecordFromAPICall = buildMarcRecordFromAPIResponse(marcRecordNode, currentBibID);

								Record currentMarcRecord = loadMarc(currentBibID);
								if (currentMarcRecord != null) {
									//TODO: Make a call to GetItemInformationRequest to get a list of the items on the Bib
									//Can also try GetHoldingsInformationRequest on CatalogAPI
									int indexOfItem;
									List<VariableField> existingItemsInMarcRecord = currentMarcRecord.getVariableFields(indexingProfile.getItemTag());
									for (VariableField itemFieldVar : existingItemsInMarcRecord) {
										DataField currentItemFromMarcRecord = (DataField) itemFieldVar;
										String currentItemID = currentItemFromMarcRecord.getSubfield(indexingProfile.getItemRecordNumberSubfield()).getData();
										if (updatedItemIDs.contains(currentItemID)) {
											// Add current Item Change Info instead
											indexOfItem = updatedItemIDs.indexOf(currentItemID);
											ItemChangeInfo updatedItem = itemUpdates.get(indexOfItem);
											if (updatedItem.getBID().equals(currentBibID)) { // Double check BID in case itemIDs aren't completely unique
												updateItemDataFieldWithChangeInfo(currentItemFromMarcRecord, updatedItem);
												itemUpdates.remove(updatedItem); // remove Item Change Info
												updatedItemIDs.remove(currentItemID); // remove itemId for list
												logger.debug("  Updating Item " + currentItemID + " in " + currentBibID);
												logger.debug(updatedItem + "\r\n" + currentItemFromMarcRecord);

											} else {
												logger.debug("  Did not update Item because BID did not match " + updatedItem.getBID() + " != " + currentBibID);
											}
										} else if (deletedItemIDs.contains(currentItemID)) {
											deletedItemIDs.remove(currentItemID);
											logger.debug("  Deleted Item " + currentItemID + " in " + currentBibID);
											continue; // Skip adding this item into the Marc Object
										} else if (createdItemIDs.contains(currentItemID)) {
											// This shouldn't happen, but in case it does
											indexOfItem = createdItemIDs.indexOf(currentItemID);
											ItemChangeInfo createdItem = createdItems.get(indexOfItem);
											if (createdItem.getBID().equals(currentBibID)) { // Double check BID in case itemIDs aren't completely unique
												updateItemDataFieldWithChangeInfo(currentItemFromMarcRecord, createdItem);

												createdItems.remove(createdItem); // remove Item Change Info
												createdItemIDs.remove(currentItemID); // remove itemId for list
												logger.debug("  Created New Item " + currentItemID + " in " + currentBibID);
											} else {
												logger.debug("  Did not create New Item because BID did not match " + createdItem.getBID() + " != " + currentBibID);
											}
										}
										updatedMarcRecordFromAPICall.addVariableField(currentItemFromMarcRecord);
									}
								} else {
									// We may lose any existing, unchanged items.
									//TODO: Make a call to GetItemInformationRequest to get a list of the items on the Bib
									logger.warn("Existing Marc Record for BID " + currentFullBibID + " failed to load; Writing new record with data from API");
									for (ItemChangeInfo updatedItem : itemUpdates) {
										if (updatedItem.getBID().equals(currentBibID)) {
											DataField newItemRecord = new DataFieldImpl(indexingProfile.getItemTag(), ' ', ' ');
											updateItemDataFieldWithChangeInfo(newItemRecord, updatedItem);
											updatedMarcRecordFromAPICall.addVariableField(newItemRecord);
										}
									}
								}

								for (ItemChangeInfo createdItem : createdItems) {
									if (createdItem.getBID().equals(currentBibID)) {
										DataField newItemRecord = new DataFieldImpl(indexingProfile.getItemTag(), ' ', ' ');
										updateItemDataFieldWithChangeInfo(newItemRecord, createdItem);
										updatedMarcRecordFromAPICall.addVariableField(newItemRecord);
									}
								}

								// Save Marc Record to File
								saveMarc(updatedMarcRecordFromAPICall, currentFullBibID);

								String permanentId = groupRecord(dbConn, updatedMarcRecordFromAPICall);
								getGroupedWorkIndexer(dbConn).processGroupedWork(permanentId);

								if (isNew){
									logEntry.incAdded();
								}else {
									logEntry.incUpdated();
								}

								numUpdates++;
								if (numUpdates % 100 == 0){
									logEntry.saveResults();
								}
							}catch (Exception e){
								logEntry.incErrors();
								logEntry.addNote("Error processing bib " + e.toString());
								logger.error("Error processing bib", e);
							}
						}
					} else {
						String shortErrorMessage = marcRecordsResponseStatus.getChildNodes().item(2).getTextContent();
						logger.error("Error Response for API call for getting Marc Records : " + shortErrorMessage);
					}
				}else{
					if (marcRecordSOAPResponse.getResponseCode() != 500){
						logger.error("API call for getting Marc Records Failed: " + marcRecordSOAPResponse.getResponseCode() + marcRecordSOAPResponse.getMessage());
						hadErrors = true;
					}
				}
			} catch (Exception e) {
				logger.error("Error Creating SOAP Request for Marc Records", e);
			}
		}
		return numUpdates;
	}

	private static boolean getChangedItemsFromCarlXApi(String beginTimeString, ArrayList<String> updatedItemIDs, ArrayList<String> createdItemIDs, ArrayList<String> deletedItemIDs){
		// Get All Changed Items //
		String changedItemsSoapRequest = "<soapenv:Envelope xmlns:soapenv=\"http://schemas.xmlsoap.org/soap/envelope/\" xmlns:mar=\"http://tlcdelivers.com/cx/schemas/marcoutAPI\" xmlns:req=\"http://tlcdelivers.com/cx/schemas/request\">\n" +
				"<soapenv:Header/>\n" +
				"<soapenv:Body>\n" +
				"<mar:GetChangedItemsRequest>\n" +
				"<mar:BeginTime>"+ beginTimeString + "</mar:BeginTime>\n" +
				"<mar:Modifiers/>\n" +
				"</mar:GetChangedItemsRequest>\n" +
				"</soapenv:Body>\n" +
				"</soapenv:Envelope>";

		WebServiceResponse SOAPResponse = NetworkUtils.postToURL(marcOutURL, changedItemsSoapRequest, "text/xml", null, logger, null, 20000, 120000);
		if (SOAPResponse.isSuccess()) {
			String totalItems;

			// Read SOAP Response for Changed Items
			try {
				Document doc = createXMLDocumentForSoapResponse(SOAPResponse);
				Node soapEnvelopeNode = doc.getFirstChild();
				Node soapBodyNode = soapEnvelopeNode.getLastChild();
				Node getChangedItemsResponseNode = soapBodyNode.getFirstChild();
				Node responseStatusNode = getChangedItemsResponseNode.getChildNodes().item(0).getChildNodes().item(0);
				String responseStatusCode = responseStatusNode.getFirstChild().getTextContent();
				if (responseStatusCode.equals("0")) {
					totalItems = responseStatusNode.getChildNodes().item(3).getTextContent();
					logger.debug("There are " + totalItems + " total items");

					Node updatedItemsNode = getChangedItemsResponseNode.getChildNodes().item(4); // 5th element of getChangedItemsResponseNode
					Node createdItemsNode = getChangedItemsResponseNode.getChildNodes().item(3); // 4th element of getChangedItemsResponseNode
					Node deletedItemsNode = getChangedItemsResponseNode.getChildNodes().item(5); // 6th element of getChangedItemsResponseNode

					// Updated Items
					getIDsArrayListFromNodeList(updatedItemsNode.getChildNodes(), updatedItemIDs);
					logger.debug("Found " + updatedItemIDs.size() + " updated items since " + beginTimeString);

					// Created Items
					getIDsArrayListFromNodeList(createdItemsNode.getChildNodes(), createdItemIDs);
					logger.debug("Found " + createdItemIDs.size() + " new items since " + beginTimeString);

					// Deleted Items
					getIDsArrayListFromNodeList(deletedItemsNode.getChildNodes(), deletedItemIDs);
					logger.debug("Found " + deletedItemIDs.size() + " deleted items since " + beginTimeString);
				} else {
					String shortErrorMessage = responseStatusNode.getChildNodes().item(2).getTextContent();
					logger.error("Error Response for API call for Changed Items : " + shortErrorMessage);
					return false;
				}

			} catch (Exception e) {
				logger.error("Error Parsing SOAP Response for Fetching Changed Items", e);
				logger.debug(SOAPResponse.getMessage());
				return false;
			}
		}else{
			logger.error("Error Calling Web Service for Fetching Changed Items");
			logEntry.addNote(SOAPResponse.getMessage());
			return false;
		}
		return true;
	}

	private static boolean getChangedBibsFromCarlXApi(String beginTimeString, ArrayList<String> updatedBibs, ArrayList<String> createdBibs, ArrayList<String> deletedBibs) {
		// Get All Changed Marc Records //
		String changedMarcSoapRequest = "<soapenv:Envelope xmlns:soapenv=\"http://schemas.xmlsoap.org/soap/envelope/\" xmlns:mar=\"http://tlcdelivers.com/cx/schemas/marcoutAPI\" xmlns:req=\"http://tlcdelivers.com/cx/schemas/request\">\n" +
				"<soapenv:Header/>\n" +
				"<soapenv:Body>\n" +
				"<mar:GetChangedBibsRequest>\n" +
				"<mar:BeginTime>"+ beginTimeString + "</mar:BeginTime>\n" +
				"<mar:SuppressionAsUpdate>1</mar:SuppressionAsUpdate>\n" +
				"<mar:Modifiers/>\n" +
				"</mar:GetChangedBibsRequest>\n" +
				"</soapenv:Body>\n" +
				"</soapenv:Envelope>";

		WebServiceResponse SOAPResponse = NetworkUtils.postToURL(marcOutURL, changedMarcSoapRequest, "text/xml", null, logger);
		if (SOAPResponse.isSuccess()) {
			String totalBibs;

			// Read SOAP Response for Changed Bibs
			try {
				Document doc = createXMLDocumentForSoapResponse(SOAPResponse);
				Node soapEnvelopeNode = doc.getFirstChild();
				Node soapBodyNode = soapEnvelopeNode.getLastChild();
				Node getChangedBibsResponseNode = soapBodyNode.getFirstChild();
				Node responseStatusNode = getChangedBibsResponseNode.getChildNodes().item(0).getChildNodes().item(0);
				String responseStatusCode = responseStatusNode.getFirstChild().getTextContent();
				if (responseStatusCode.equals("0")) {
					totalBibs = responseStatusNode.getChildNodes().item(3).getTextContent();
					logger.debug("There are " + totalBibs + " total bibs");
					Node updatedBibsNode = getChangedBibsResponseNode.getChildNodes().item(4); // 5th element of getChangedItemsResponseNode
					Node createdBibsNode = getChangedBibsResponseNode.getChildNodes().item(3); // 4th element of getChangedItemsResponseNode
					Node deletedBibsNode = getChangedBibsResponseNode.getChildNodes().item(5); // 6th element of getChangedItemsResponseNode

					// Updated Items
					getIDsFromNodeList(updatedBibs, updatedBibsNode.getChildNodes());
					logger.debug("Found " + updatedBibs.size() + " updated bibs since " + beginTimeString);

					// TODO: Process Created Bibs in the future.
					// Created Bibs
					getIDsFromNodeList(createdBibs, createdBibsNode.getChildNodes());
					logger.debug("Found " + createdBibs.size() + " new bibs since " + beginTimeString);

					// TODO: Process Deleted Bibs in the future
					// Deleted Bibs
					getIDsFromNodeList(deletedBibs, deletedBibsNode.getChildNodes());
					logger.debug("Found " + deletedBibs.size() + " deleted bibs since " + beginTimeString);

				} else {
					String shortErrorMessage = responseStatusNode.getChildNodes().item(2).getTextContent();
					logger.error("Error Response for API call for Changed Bibs : " + shortErrorMessage);
					return false;
				}


			} catch (Exception e) {
				logger.error("Error Parsing SOAP Response for Fetching Changed Bibs", e);
				return false;
			}
		}else{
			logger.error("Did not get a successful response from getChangedBibsFromCarlXApi");
			return false;
		}
		return true;
	}

	private static Record buildMarcRecordFromAPIResponse(Node marcRecordNode, String currentBibID) {
		NodeList marcFields = marcRecordNode.getChildNodes();
		int numFields   = marcFields.getLength();

		Record updatedMarcRecordFromAPICall = MarcFactoryImpl.newInstance().newRecord();

		// Put XML data in the Record Object
		for (int j=0; j < numFields; j++) {
			Node marcField   = marcFields.item(j);
			String fieldName = marcField.getNodeName().replaceFirst("ns4:", "");
			switch (fieldName) {
				case "leader" :
					// Set Leader
					String leader = marcField.getTextContent();
					updatedMarcRecordFromAPICall.setLeader(MarcFactoryImpl.newInstance().newLeader(leader));
					break;
				case "controlField" :
					// Set Control Field
					String field = marcField.getTextContent();
					field = field.replace("{U+001E}", ""); // get rid of unicode characters at the end of some control fields.
					String tag;
					if (marcField.hasAttributes()) {
						NamedNodeMap attributes = marcField.getAttributes();
						Node attributeNode      = attributes.getNamedItem("tag");
						tag                     = attributeNode.getTextContent();
						updatedMarcRecordFromAPICall.addVariableField(MarcFactoryImpl.newInstance().newControlField(tag, field));
					} else {
						logger.warn("CarlX MarcOut data for a control field had no attributes. Could not update control field for BibID " + currentBibID);
					}
					break;
				case "dataField" :
					// Set data Field
					if (marcField.hasAttributes()) {
						// Get Tag Number
						NamedNodeMap attributes = marcField.getAttributes();
						Node attributeNode      = attributes.getNamedItem("tag");
						tag                     = attributeNode.getTextContent();

						// Get first indicator
						attributeNode        = attributes.getNamedItem("ind1");
						String tempString    = attributeNode.getNodeValue();
//												String tempString     = attributeNode.getTextContent();
						char indicator1 = tempString.charAt(0);

						// Get second indicator
						attributeNode        = attributes.getNamedItem("ind2");
						tempString           = attributeNode.getNodeValue();
//												tempString            = attributeNode.getTextContent();
						char indicator2 = tempString.charAt(0);

						// Go through sub-fields
						NodeList subFields   = marcField.getChildNodes();
						int numSubFields = subFields.getLength();

						// Initialize data field
						DataField dataField = MarcFactoryImpl.newInstance().newDataField(tag, indicator1, indicator2);

						// Add all subFields to the data field
						for (int k=0; k < numSubFields; k++) {
							Node subFieldNode = subFields.item(k);
							if (marcField.hasAttributes()) {
								attributes           = subFieldNode.getAttributes();
								attributeNode        = attributes.getNamedItem("code");
								tempString           = attributeNode.getNodeValue();
								char code       = tempString.charAt(0);
								String subFieldValue = subFieldNode.getTextContent();
								Subfield subfield    = MarcFactoryImpl.newInstance().newSubfield(code, subFieldValue);
								dataField.addSubfield(subfield);
							}
						}

						// Add Data Field to MARC object
						updatedMarcRecordFromAPICall.addVariableField(dataField);

					} else {
						logger.warn("CarlX MarcOut data for a data field had no attributes. Could not update data field for BibID " + currentBibID);
					}
			}
		}
		return updatedMarcRecordFromAPICall;
	}

	private static ArrayList<ItemChangeInfo> fetchItemInformation(ArrayList<String> itemIDs) {
		hadErrors = false;
		ArrayList<ItemChangeInfo> itemUpdates = new ArrayList<>();
		logger.debug("Getting item information for " + itemIDs.size() + " Item IDs");
		if (itemIDs.size() > 100){
			logger.warn("There are more than 100 items that need updates " + itemIDs.size());
		}
		while (itemIDs.size() > 0) {
			//Set an upper limit on number of IDs for one request, and process in batches
			StringBuilder getItemInformationSoapRequest;
			String getItemInformationSoapRequestStart = "<soapenv:Envelope xmlns:soapenv=\"http://schemas.xmlsoap.org/soap/envelope/\" xmlns:mar=\"http://tlcdelivers.com/cx/schemas/marcoutAPI\" xmlns:req=\"http://tlcdelivers.com/cx/schemas/request\">\n" +
					"<soapenv:Header/>\n" +
					"<soapenv:Body>\n" +
					"<mar:GetItemInformationRequest>\n" +
					"<mar:ItemSearchType>ITEM</mar:ItemSearchType>\n";
			String getItemInformationSoapRequestEnd =
					"<mar:IncludeSuppressItems>true</mar:IncludeSuppressItems>\n" + // TODO: Do we want this on??
							"<mar:Modifiers>\n" +
							"</mar:Modifiers>\n" +
							"</mar:GetItemInformationRequest>\n" +
							"</soapenv:Body>\n" +
							"</soapenv:Envelope>";
			try {
				getItemInformationSoapRequest = new StringBuilder(getItemInformationSoapRequestStart);
				// Updated Items
				@SuppressWarnings("unchecked")
				ArrayList<String> itemsCopy = (ArrayList<String>)itemIDs.clone();
				int numAdded = 0;
				for (String updatedItem : itemsCopy) {
					getItemInformationSoapRequest.append("<mar:ItemSearchTerm>").append(updatedItem).append("</mar:ItemSearchTerm>\n");
					numAdded++;
					itemIDs.remove(updatedItem);
					if (numAdded >= 100){
						break;
					}
				}
				getItemInformationSoapRequest.append(getItemInformationSoapRequestEnd);

				WebServiceResponse ItemInformationSOAPResponse = NetworkUtils.postToURL(marcOutURL, getItemInformationSoapRequest.toString(), "text/xml", null, logger);
				if (ItemInformationSOAPResponse.isSuccess()) {

					// Parse Response
					Document doc = createXMLDocumentForSoapResponse(ItemInformationSOAPResponse);
					Node soapEnvelopeNode = doc.getFirstChild();
					Node soapBodyNode = soapEnvelopeNode.getLastChild();
					Node getItemInformationResponseNode = soapBodyNode.getFirstChild();
					Node responseStatus = getItemInformationResponseNode.getFirstChild().getFirstChild();
					// There is a Response Statuses Node, which then contains the Response Status Node
					String responseStatusCode = responseStatus.getFirstChild().getTextContent();
					logger.debug("Item information response " + doc.toString());
					if (responseStatusCode.equals("0")) { // Successful response

						NodeList ItemStatuses = getItemInformationResponseNode.getChildNodes();

						int l = ItemStatuses.getLength();
						for (int i = 1; i < l; i++) {
							// start with i = 1 to skip first node, because that is the response status node and not an item status

							Node itemStatus = ItemStatuses.item(i);
							if (itemStatus.getNodeName().contains("ItemStatus")) { // avoid other occasional nodes like "Message"

								NodeList itemDetails = itemStatus.getChildNodes();
								ItemChangeInfo currentItem = new ItemChangeInfo();

								int dl = itemDetails.getLength();
								for (int j = 0; j < dl; j++) {
									Node detail = itemDetails.item(j);
									String detailName = detail.getNodeName();
									String detailValue = detail.getTextContent();

									detailName = detailName.replaceFirst("ns4:", ""); // strip out namespace prefix

									// Handle each detail
									switch (detailName) {
										case "BID":
											currentItem.setBID(detailValue);
											break;
										case "ItemID":
											currentItem.setItemId(detailValue);
											break;
										case "LocationCode":
											currentItem.setShelvingLocation(detailValue);
											break;
										case "StatusCode":
											/*// Set itemIdentifier for logging with info that we know at this point.
											String itemIdentifier;
											// Use code below if we every turn on switch fullReindex (logs missing translation values)
											if (currentItem.getBID().isEmpty()) {
												itemIdentifier = currentItem.getItemId().isEmpty() ? "a Carl-X Item" : " for item ID " + currentItem.getItemId();
											} else {
												itemIdentifier = currentItem.getItemId().isEmpty() ? currentItem.getBID() + " for an unknown Carl-X Item" : currentItem.getBID() + " for item ID " + currentItem.getItemId();
											}
											String statusCode = translateValue("status_codes", detailValue, itemIdentifier);
											if (statusCode.equals("U")) {
												logger.warn("Unknown status " + detailValue);
											}*/
											currentItem.setStatus(detailValue);
											break;
										case "DueDate":
											String dueDateMarc = formatDateFieldForMarc(indexingProfile.getDueDateFormat(), detailValue);
											logger.debug("New due date is " + dueDateMarc + " based on info from CARL.X " + detailValue);
											currentItem.setDueDate(dueDateMarc);
											break;
										case "LastCheckinDate":
											// There is no LastCheckinDate field in ItemInformation Call
											String lastCheckInDateMarc = formatDateFieldForMarc(indexingProfile.getLastCheckinFormat(), detailValue);
											currentItem.setLastCheckinDate(lastCheckInDateMarc);
											logger.debug("New last check in date is " + lastCheckInDateMarc + " based on info from CARL.X " + detailValue);
											break;
										case "CreationDate":
											String dateCreatedMarc = formatDateFieldForMarc(indexingProfile.getDateCreatedFormat(), detailValue);
											currentItem.setDateCreated(dateCreatedMarc);
											logger.debug("New date created is " + dateCreatedMarc + " based on info from CARL.X " + detailValue);
											break;
										case "CallNumber":
										case "CallNumberFull":
											currentItem.setCallNumber(detailValue);
											break;
										case "CircHistory": // total since counter reset: translating to total checkout per year
											currentItem.setYearToDateCheckouts(detailValue);
											break;
										case "CumulativeHistory":
											currentItem.setTotalCheckouts(detailValue);
											break;
										case "BranchCode":
											currentItem.setLocation(detailValue);
											break;
										case "MediaCode":
											currentItem.setiType(detailValue);
											break;
										// Fields we don't currently do anything with
										case "Suppress":
											//logger.debug("Suppression for item is " + detailValue);
											currentItem.setSuppress(detailValue);
										case "HoldsHistory": // Number of times item has gone to Hold Shelf status since counter set
										case "InHouseCirc":
										case "Price":
										case "ReserveBranchCode":
										case "ReserveType":
										case "ReserveBranchLocation":
										case "ReserveCallNumber":
										case "BranchName":
										case "BranchNumber":
										case "StatusDate": //TODO: can we use this one?
										case "ThereAtLeastOneNote":
										case "Notes":
										case "EditDate":
										case "CNLabels":
										case "Caption":
										case "Number":
										case "Part":
										case "Volume":
										case "Suffix":
											//									CNLabels: Labels for the 4 call number buckets
											//									Number: Third call number bucket
											//									Part: Second call number bucket
											//									Volume: First call number bucket
											//									Suffix: Fourth call number bucket
										case "ISID":
										case "Chronology":
										case "Enumeration":
										case "OwningBranchCode":
										case "OwningBranchName":
										case "OwningBranchNumber":
										case "Type":
										case "Status":
										case "AlternateStatus":
										case "MediaNumber":
										case "CreatedBy":
										case "LastUpdatedBy":
										case "LocationName":
										case "LocationNumber":
										case "OwningLocationCode":
										case "OwningLocationName":
										case "OwningLocationNumber":
											// Do Nothing
											break;
										default:
											logger.warn("Unknown Item Detail : " + detailName + " = " + detailValue);
											break;
									}
								}
								itemUpdates.add(currentItem);
							}
						}
					} else {
						logger.error("Did not get a successful SOAP response " + responseStatusCode + " loading item information");
						logEntry.addNote("Did not get a successful SOAP response " + responseStatusCode + " loading item information");
						hadErrors = true;
					}
				}else{
					logger.error("Did not get a successful SOAP response " + ItemInformationSOAPResponse.getResponseCode() + "\r\n" + ItemInformationSOAPResponse.getMessage());
					logEntry.addNote("Did not get a successful SOAP response " + ItemInformationSOAPResponse.getResponseCode() + "<br/>" + ItemInformationSOAPResponse.getMessage());
					hadErrors = true;
				}
			} catch (Exception e) {
				logger.error("Error Retrieving SOAP updated items", e);
				logEntry.addNote("Error Retrieving SOAP updated items " + e.toString());
				hadErrors = true;
			}
		}
		return itemUpdates;
	}

	private static void getIDsFromNodeList(ArrayList<String> arrayOfIds, NodeList walkThroughMe) {
		int l       = walkThroughMe.getLength();
		for (int i = 0; i < l; i++) {
			arrayOfIds.add(walkThroughMe.item(i).getTextContent());
		}
	}

	private static String formatDateFieldForMarc(String dateFormat, String date) {
		String dateForMarc = null;
		try {
			String itemInformationDateFormat = "yyyy-MM-dd'T'HH:mm:ss.SSSXXX";
			SimpleDateFormat dateFormatter = new SimpleDateFormat(itemInformationDateFormat);
			dateFormatter.setTimeZone(TimeZone.getTimeZone("UTC"));
			Date marcDate = dateFormatter.parse(date);
			SimpleDateFormat marcDateCreatedFormat = new SimpleDateFormat(dateFormat);
			dateForMarc = marcDateCreatedFormat.format(marcDate);
		} catch (Exception e) {
			logger.error("Error while formatting a date field for Marc Record", e);
		}
		return dateForMarc;
	}

	private static void getIDsArrayListFromNodeList(NodeList walkThroughMe, ArrayList<String> idList) {
		int l                = walkThroughMe.getLength();
		for (int i = 0; i < l; i++) {
			String itemID = walkThroughMe.item(i).getTextContent();
			idList.add(itemID);
		}
	}

	private static void updateItemDataFieldWithChangeInfo(DataField itemField, ItemChangeInfo changeInfo) {
		updateItemSubfield(itemField, indexingProfile.getItemRecordNumberSubfield(), changeInfo.getItemId());
		if (indexingProfile.getCallNumberSubfield() != ' ' && !changeInfo.getCallNumber().isEmpty()) {
			updateItemSubfield(itemField, indexingProfile.getCallNumberSubfield(), changeInfo.getCallNumber());
		}
		updateItemSubfield(itemField, indexingProfile.getLocationSubfield(), changeInfo.getLocation());
		updateItemSubfield(itemField, indexingProfile.getShelvingLocationSubfield(), changeInfo.getShelvingLocation());
		if (indexingProfile.getITypeSubfield() != ' ' && !changeInfo.getYearToDateCheckouts().isEmpty()) {
			updateItemSubfield(itemField, indexingProfile.getITypeSubfield(), changeInfo.getiType());
		}
		updateItemSubfield(itemField, indexingProfile.getItemStatusSubfield(), changeInfo.getStatus());

		if (indexingProfile.getTotalCheckoutsSubfield() != ' ' && !changeInfo.getTotalCheckouts().isEmpty()) {
			updateItemSubfield(itemField, indexingProfile.getTotalCheckoutsSubfield(), changeInfo.getTotalCheckouts());
		}

		if (indexingProfile.getYearToDateCheckoutsSubfield() != ' ' && !changeInfo.getYearToDateCheckouts().isEmpty()) {
			updateItemSubfield(itemField, indexingProfile.getYearToDateCheckoutsSubfield(), changeInfo.getYearToDateCheckouts());
		}



		if (indexingProfile.getDueDateSubfield() != ' ') {
			if (changeInfo.getDueDate() == null) {
				if (itemField.getSubfield(indexingProfile.getDueDateSubfield()) != null) {
					if (indexingProfile.getDueDateFormat().contains("-")){
						updateItemSubfield(itemField, indexingProfile.getDueDateSubfield(), "  -  -  ");
					} else {
						updateItemSubfield(itemField, indexingProfile.getDueDateSubfield(), "      ");
					}
				}
			} else {
				updateItemSubfield(itemField, indexingProfile.getDueDateSubfield(), changeInfo.getDueDate());
			}
		}

		if (indexingProfile.getDateCreatedSubfield() != ' ') {
			if (changeInfo.getDateCreated() == null) {
				if (itemField.getSubfield(indexingProfile.getDateCreatedSubfield()) != null) {
					if (indexingProfile.getDateCreatedFormat().contains("-")){
						updateItemSubfield(itemField, indexingProfile.getDateCreatedSubfield(), "  -  -  ");
					} else {
						updateItemSubfield(itemField, indexingProfile.getDateCreatedSubfield(), "      ");
					}
				}
			} else {
				updateItemSubfield(itemField, indexingProfile.getDateCreatedSubfield(), changeInfo.getDateCreated());
			}
		}

		if (indexingProfile.getLastCheckinDateSubfield() != ' ') {
			if (changeInfo.getLastCheckinDate() == null) {
				if (itemField.getSubfield(indexingProfile.getLastCheckinDateSubfield()) != null) {
					if (indexingProfile.getLastCheckinFormat().contains("-")) {
						updateItemSubfield(itemField, indexingProfile.getLastCheckinDateSubfield(), "  -  -  ");
					} else {
						updateItemSubfield(itemField, indexingProfile.getLastCheckinDateSubfield(), "      ");
					}
				}
			} else {
				updateItemSubfield(itemField, indexingProfile.getLastCheckinDateSubfield(), changeInfo.getLastCheckinDate());
			}
		}
	}

	private static void updateItemSubfield(DataField itemField, char subfield, String value) {
		if (itemField.getSubfield(subfield) == null) {
			itemField.addSubfield(new SubfieldImpl(subfield, value));
		} else {
			itemField.getSubfield(subfield).setData(value);
		}
	}

	private static Record loadMarc(String curBibId) {
		//Load the existing marc record from file
		try {
			logger.debug("Loading MARC for " + curBibId);
			File marcFile = indexingProfile.getFileForIlsRecord(getFileIdForRecordNumber(curBibId));
			if (marcFile.exists()) {
				FileInputStream inputStream = new FileInputStream(marcFile);
				MarcPermissiveStreamReader marcReader = new MarcPermissiveStreamReader(inputStream, true, true, "UTF-8");
				if (marcReader.hasNext()) {
					Record marcRecord = marcReader.next();
					inputStream.close();
					return marcRecord;
				} else {
					logger.info("Could not read marc record for " + curBibId + ". The bib was empty");
				}
			}else{
				logger.debug("Marc Record does not exist for " + curBibId + " (" + marcFile.getAbsolutePath() + "). It is not part of the main extract yet.");
			}
		}catch (Exception e){
			logger.error("Error updating marc record for bib " + curBibId, e);
		}
		return null;
	}

	private static void saveMarc(Record marcObject, String curBibId) {
		//Write the new marc record
		File marcFile = indexingProfile.getFileForIlsRecord(curBibId);

		MarcWriter writer;
		try {
			writer = new MarcStreamWriter(new FileOutputStream(marcFile, false), "UTF-8", true);
			writer.write(marcObject);
			writer.close();
			logger.debug("  Created Saved updated MARC record to " + marcFile.getAbsolutePath());
		} catch (FileNotFoundException e) {
			logger.error("Error saving marc record for bib " + curBibId, e);
		}
	}

	private static String getFileIdForRecordNumber(String recordNumber) {
		if (recordNumber.startsWith("CARL")){
			return recordNumber;
		}
		StringBuilder recordNumberBuilder = new StringBuilder(recordNumber);
		while (recordNumberBuilder.length() < 10){ // pad up to a 10-digit number
			recordNumberBuilder.insert(0, "0");
		}
		recordNumber = recordNumberBuilder.toString();
		return "CARL" + recordNumber; // add Carl prefix
	}

	private static Document createXMLDocumentForSoapResponse(WebServiceResponse SoapResponse) throws ParserConfigurationException, IOException, SAXException {
		DocumentBuilderFactory dbFactory = DocumentBuilderFactory.newInstance();
		dbFactory.setValidating(false);
		dbFactory.setIgnoringElementContentWhitespace(true);

		DocumentBuilder dBuilder = dbFactory.newDocumentBuilder();

		byte[]                soapResponseByteArray            = SoapResponse.getMessage().getBytes(StandardCharsets.UTF_8);
		ByteArrayInputStream  soapResponseByteArrayInputStream = new ByteArrayInputStream(soapResponseByteArray);
		InputSource           soapResponseInputSource          = new InputSource(soapResponseByteArrayInputStream);

		Document doc = dBuilder.parse(soapResponseInputSource);
		doc.getDocumentElement().normalize();

		return doc;
	}

	private static void exportHolds(Connection carlxConn, Connection dbConn) {

		Savepoint startOfHolds = null;
		try {
			logger.info("Starting export of holds");

			PreparedStatement addIlsHoldSummary = dbConn.prepareStatement("INSERT INTO ils_hold_summary (ilsId, numHolds) VALUES (?, ?)");

			HashMap<String, Long> numHoldsByBib = new HashMap<>();

			//Export bib level holds
			PreparedStatement bibHoldsStmt = carlxConn.prepareStatement("select bid,sum(count) numHolds from (\n" +
					"  select bid,count(1) count from transbid_v group by bid\n" +
					"  UNION ALL\n" +
					"  select bid,count(1) count from transitem_v, item_v where\n" +
					"    transcode like 'R%' and transitem_v.item=item_v.item\n" +
					"  group by bid)\n" +
					"group by bid\n" +
					"order by bid", ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY);
			ResultSet bibHoldsRS = bibHoldsStmt.executeQuery();
			while (bibHoldsRS.next()){
				StringBuilder bibIdFull = new StringBuilder(bibHoldsRS.getString("bid"));
				while (bibIdFull.length() < 10){
					bibIdFull.insert(0, "0");
				}
				bibIdFull.insert(0, "CARL");
				Long numHolds = bibHoldsRS.getLong("numHolds");
				numHoldsByBib.put(bibIdFull.toString(), numHolds);
			}
			bibHoldsRS.close();

			//Start a transaction so we can rebuild an entire table
			startOfHolds = dbConn.setSavepoint();
			dbConn.setAutoCommit(false);
			//Delete existing holds closer to the time that holds are re-added.  This shouldn't matter since auto commit is off though
			dbConn.prepareCall("TRUNCATE TABLE ils_hold_summary").executeQuery();
			logger.debug("Found " + numHoldsByBib.size() + " bibs that have title or item level holds");

			for (String bibId : numHoldsByBib.keySet()){
				addIlsHoldSummary.setString(1, bibId);
				addIlsHoldSummary.setLong(2, numHoldsByBib.get(bibId));
				addIlsHoldSummary.executeUpdate();
			}

			try {
				dbConn.commit();
				dbConn.setAutoCommit(true);
			}catch (Exception e){
				logger.warn("error committing hold updates rolling back", e);
				dbConn.rollback(startOfHolds);
				startOfHolds = null;
			}

		} catch (Exception e) {
			logger.error("Unable to export holds from CARL.X", e);
			if (startOfHolds != null) {
				try {
					dbConn.rollback(startOfHolds);
				}catch (Exception e1){
					logger.error("Unable to rollback due to exception", e1);
				}
			}
		}
		logger.info("Finished exporting holds");
	}

	private static String groupRecord(Connection dbConn, Record marcRecord) {
		return getRecordGroupingProcessor(dbConn).processMarcRecord(marcRecord, true);
	}

	private static MarcRecordGrouper getRecordGroupingProcessor(Connection dbConn){
		if (recordGroupingProcessorSingleton == null) {
			recordGroupingProcessorSingleton = new MarcRecordGrouper(serverName, dbConn, indexingProfile, logger, false);
		}
		return recordGroupingProcessorSingleton;
	}

	private static GroupedWorkIndexer getGroupedWorkIndexer(Connection dbConn) {
		if (groupedWorkIndexer == null) {
			groupedWorkIndexer = new GroupedWorkIndexer(serverName, dbConn, configIni, false, false, false, logger);
		}
		return groupedWorkIndexer;
	}
}
