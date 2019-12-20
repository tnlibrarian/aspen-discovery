<?php

require_once ROOT_DIR . '/RecordDrivers/HooplaRecordDriver.php';

class Hoopla_Home extends Action{
	/** @var  SearchObject_GroupedWorkSearcher $db */
	protected $db;
	private $id;

	function launch(){
		global $interface;

		if (isset($_REQUEST['searchId'])){
			$_SESSION['searchId'] = $_REQUEST['searchId'];
			$interface->assign('searchId', $_SESSION['searchId']);
		}else if (isset($_SESSION['searchId'])){
			$interface->assign('searchId', $_SESSION['searchId']);
		}

		$this->id = strip_tags($_REQUEST['id']);
		$interface->assign('id', $this->id);
		$recordDriver = new HooplaRecordDriver($this->id);

		if (!$recordDriver->isValid()){
			$this->display('../Record/invalidRecord.tpl', 'Invalid Record');
			die();
		}
		$groupedWork = $recordDriver->getGroupedWorkDriver();
		if (is_null($groupedWork) || !$groupedWork->isValid()){  // initRecordDriverById itself does a validity check and returns null if not.
			$this->display('../Record/invalidRecord.tpl', 'Invalid Record');
			die();
		}else{
			$interface->assign('recordDriver', $recordDriver);

			$summaryActions = $recordDriver->getActions();
			$interface->assign('summaryActions', $summaryActions);

			//Load the citations
			$this->loadCitations($recordDriver);

			// Retrieve User Search History
			$interface->assign('lastSearch', isset($_SESSION['lastSearchURL']) ? $_SESSION['lastSearchURL'] : false);

			//Get Next/Previous Links
			$searchSource = !empty($_REQUEST['searchSource']) ? $_REQUEST['searchSource'] : 'local';
			$searchObject = SearchObjectFactory::initSearchObject();
			$searchObject->init($searchSource);
			$searchObject->getNextPrevLinks();

			// Set Show in Main Details Section options for templates
			// (needs to be set before moreDetailsOptions)
			global $library;
			foreach ($library->getGroupedWorkDisplaySettings()->showInMainDetails as $detailoption) {
				$interface->assign($detailoption, true);
			}

			$interface->assign('moreDetailsOptions', $recordDriver->getMoreDetailsOptions());

			$interface->assign('semanticData', json_encode($recordDriver->getSemanticData()));

			// Display Page
			$this->display('full-record.tpl', $recordDriver->getTitle());
		}
	}

	/**
	 * @param HooplaRecordDriver $recordDriver
	 */
	function loadCitations($recordDriver)
	{
		global $interface;

		$citationCount = 0;
		$formats = $recordDriver->getCitationFormats();
		foreach($formats as $current) {
			$interface->assign(strtolower($current), $recordDriver->getCitation($current));
			$citationCount++;
		}
		$interface->assign('citationCount', $citationCount);
	}

}