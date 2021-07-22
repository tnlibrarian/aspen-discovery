<?php

require_once ROOT_DIR . '/Drivers/AbstractEContentDriver.php';
class Axis360Driver extends AbstractEContentDriver
{
	/** @var CurlWrapper */
	private $curlWrapper;
	private $accessToken = null;
	private $accessTokenExpiration = 0;

	public function initCurlWrapper()
	{
		$this->curlWrapper = new CurlWrapper();
		$this->curlWrapper->timeout = 20;
	}

	public function hasNativeReadingHistory()
	{
		return false;
	}

	private function getAxis360AccessToken(User $user = null) {
		$settings = $this->getSettings($user);
		if ($settings == false){
			return false;
		}
		$now = time();
		if ($this->accessToken == null || $this->accessTokenExpiration <= $now){
			$authentication = $settings->vendorUsername . ':' . $settings->vendorPassword . ':' . $settings->libraryPrefix;
			$utf16Authentication = iconv('UTF-8', 'UTF-16LE', $authentication);
			$authorizationUrl = $settings->apiUrl . '/Services/VendorAPI/accesstoken';
			$headers = [
				"Authorization: Basic " . base64_encode($utf16Authentication),
			];
			$authorizationCurlWrapper = new CurlWrapper();
			$authorizationCurlWrapper->addCustomHeaders($headers, true);
			$authorizationResponse = $authorizationCurlWrapper->curlPostPage($authorizationUrl, "");
			$authorizationCurlWrapper->close_curl();
			if ($authorizationResponse){
				$jsonResponse = json_decode($authorizationResponse);
				$this->accessToken = $jsonResponse->access_token;
				$this->accessTokenExpiration = $now + ($jsonResponse->expires_in - 5);
				return true;
			}else{
				$this->incrementStat('numConnectionFailures');
				return false;
			}
		}else{
			return true;
		}
	}


	private $checkouts = [];
	/**
	 * Get Patron Checkouts
	 *
	 * This is responsible for retrieving all checkouts (i.e. checked out items)
	 * by a specific patron.
	 *
	 * @param User $patron The user to load transactions for
	 * @return Checkout[]        Array of the patron's transactions on success
	 * @access public
	 */
	public function getCheckouts(User $patron)
	{
		require_once ROOT_DIR . '/sys/User/Checkout.php';
		if (isset($this->checkouts[$patron->id])){
			return $this->checkouts[$patron->id];
		}
		$checkouts = [];
		$settings = $this->getSettings($patron);
		if ($settings == false){
			return $checkouts;
		}
		if ($this->getAxis360AccessToken($patron)){
			$checkoutsUrl = $settings->apiUrl . "/Services/VendorAPI/availability/v3_1";
			$params = [
				'statusFilter' => 'CHECKOUT',
				'patronId' => $patron->getBarcode()
			];
			$headers = [
				'Authorization: ' . $this->accessToken,
				'Library: ' . $settings->libraryPrefix,
			];
			$this->initCurlWrapper();
			$this->curlWrapper->addCustomHeaders($headers, false);
			$response = $this->curlWrapper->curlPostPage($checkoutsUrl, $params);
			/** @var stdClass $xmlResults */
			$xmlResults = simplexml_load_string($response);
			$status = $xmlResults->status;
			if ($status->code == '0000'){
				foreach ($xmlResults->title as $title){
					$this->loadCheckoutInfo($title, $checkouts, $patron);
				}
			}else{
				global $logger;
				$logger->log('Error loading checkouts ' . $status->statusMessage, Logger::LOG_ERROR);
				$this->incrementStat('numApiErrors');
			}

			return $checkouts;
		}else{
			return $checkouts;
		}
	}

	/**
	 * @return boolean true if the driver can renew all titles in a single pass
	 */
	public function hasFastRenewAll()
	{
		return false;
	}

	/**
	 * Renew all titles currently checked out to the user
	 *
	 * @param $patron  User
	 * @return mixed
	 */
	public function renewAll(User $patron)
	{
		return false;
	}

	/**
	 * Renew a single title currently checked out to the user
	 *
	 * @param $patron     User
	 * @param $recordId   string
	 * @return mixed
	 */
	function renewCheckout($patron, $recordId, $itemId = null, $itemIndex = null)
	{
		return $this->checkOutTitle($patron, $recordId, true);
	}

	/**
	 * Return a title currently checked out to the user
	 *
	 * @param $patron User
	 * @param $transactionId   string
	 * @return array
	 */
	public function returnCheckout($patron, $transactionId)
	{
		$result = ['success' => false, 'message' => 'Unknown error'];
		if ($this->getAxis360AccessToken()){
			$settings = $this->getSettings();
			$returnCheckoutUrl = $settings->apiUrl . "/Services/VendorAPI/EarlyCheckin/v2?transactionID=$transactionId";
			$headers = [
				'Authorization: ' . $this->accessToken,
				'Library: ' . $settings->libraryPrefix,
			];
			$this->initCurlWrapper();
			$this->curlWrapper->addCustomHeaders($headers, false);
			$response = $this->curlWrapper->curlGetPage($returnCheckoutUrl);
			/** @var stdClass $xmlResults */
			$xmlResults = simplexml_load_string($response);
			$removeHoldResult = $xmlResults->EarlyCheckinRestResult;
			$status = $removeHoldResult->status;
			if ($status->code != '0000'){
				$result['message'] = "Could not cancel return title, " . (string)$status->statusMessage;
				$this->incrementStat('numApiErrors');
			}else{
				$result['success'] = true;
				$result['message'] = 'Your title was returned successfully';
				$this->incrementStat('numEarlyReturns');
				$patron->clearCachedAccountSummaryForSource('axis360');
				$patron->forceReloadOfCheckouts();
			}
		}else{
			$result['message'] = 'Unable to connect to Axis 360';
		}
		return $result;
	}

	private $holds = [];
	/**
	 * Get Patron Holds
	 *
	 * This is responsible for retrieving all holds for a specific patron.
	 *
	 * @param User $patron The user to load transactions for
	 * @param bool $forSummary
	 *
	 * @return array        Array of the patron's holds
	 * @access public
	 */
	public function getHolds($patron, $forSummary = false)
	{
		require_once ROOT_DIR . '/sys/User/Hold.php';
		if (isset($this->holds[$patron->id])){
			return $this->holds[$patron->id];
		}
		$holds = array(
			'available' => array(),
			'unavailable' => array()
		);
		$settings = $this->getSettings($patron);
		if ($settings == false){
			return $holds;
		}

		if ($this->getAxis360AccessToken($patron)){
			$holdUrl = $settings->apiUrl . "/Services/VendorAPI/GetHolds/{$patron->getBarcode()}";
			$headers = [
				'Authorization: ' . $this->accessToken,
				'Library: ' . $settings->libraryPrefix,
			];
			$this->initCurlWrapper();
			$this->curlWrapper->addCustomHeaders($headers, false);
			$response = $this->curlWrapper->curlSendPage($holdUrl, 'GET');
			/** @var stdClass $xmlResults */
			$xmlResults = simplexml_load_string($response);
			$holdsResult = $xmlResults->getHoldsResult;
			if (!empty($holdsResult->holds)){
				foreach ($holdsResult->holds->hold as $hold){
					$this->loadHoldInfo($hold, $holds, $patron, $forSummary);
				}
			}

		}

		return $holds;
	}

	/**
	 * Place Hold
	 *
	 * This is responsible for both placing holds as well as placing recalls.
	 *
	 * @param User $patron The User to place a hold for
	 * @param string $recordId The id of the bib record
	 * @return  array                 An array with the following keys
	 *                                result - true/false
	 *                                message - the message to display (if item holds are required, this is a form to select the item).
	 *                                needsItemLevelHold - An indicator that item level holds are required
	 *                                title - the title of the record the user is placing a hold on
	 * @access  public
	 */
	function placeHold($patron, $recordId, $pickupBranch = null, $cancelDate = null)
	{
		$result = ['success' => false, 'message' => 'Unknown error'];
		if ($this->getAxis360AccessToken($patron)) {
			$settings = $this->getSettings($patron);
			$holdUrl = $settings->apiUrl . "/Services/VendorAPI/addToHold/v2/$recordId/" . urlencode($patron->email) . "/{$patron->getBarcode()}";
			$headers = [
				'Authorization: ' . $this->accessToken,
				'Library: ' . $settings->libraryPrefix,
			];
			$this->initCurlWrapper();
			$this->curlWrapper->addCustomHeaders($headers, false);
			$response = $this->curlWrapper->curlSendPage($holdUrl, 'GET');
			/** @var stdClass $xmlResults */
			$xmlResults = simplexml_load_string($response);
			$addToHoldResult = $xmlResults->addtoholdResult;
			$status = $addToHoldResult->status;
			if ($status->code == '3111') {
				//The title is available, try to check it out.
				return $this->checkOutTitle($patron, $recordId, false);
			}else if ($status->code != '0000'){
				$result['message'] = "Could not place hold, " . (string)$status->statusMessage;
				$this->incrementStat('numApiErrors');
			}else{
				$result['success'] = true;
				$result['message'] = 'Your hold was placed successfully';
				$this->incrementStat('numHoldsPlaced');
				$this->trackUserUsageOfAxis360($patron);
				$this->trackRecordHold($recordId);
				$patron->clearCachedAccountSummaryForSource('axis360');
				$patron->forceReloadOfHolds();
			}

		}else{
			$result['message'] = 'Unable to connect to Axis 360';
		}
		return $result;
	}

	/**
	 * Cancels a hold for a patron
	 *
	 * @param User $patron The User to cancel the hold for
	 * @param string $recordId The id of the bib record
	 * @return  array
	 */
	function cancelHold($patron, $recordId, $cancelId = null)
	{
		$result = ['success' => false, 'message' => 'Unknown error'];
		if ($this->getAxis360AccessToken($patron)){
			$settings = $this->getSettings($patron);
			$cancelHoldUrl = $settings->apiUrl . "/Services/VendorAPI/removeHold/v2/$recordId/{$patron->getBarcode()}";
			$headers = [
				'Authorization: ' . $this->accessToken,
				'Library: ' . $settings->libraryPrefix,
			];
			$this->initCurlWrapper();
			$this->curlWrapper->addCustomHeaders($headers, false);
			$response = $this->curlWrapper->curlSendPage($cancelHoldUrl, 'GET');
			/** @var stdClass $xmlResults */
			$xmlResults = simplexml_load_string($response);
			$removeHoldResult = $xmlResults->removeholdResult;
			$status = $removeHoldResult->status;
			if ($status->code != '0000'){
				$result['message'] = "Could not cancel hold, " . (string)$status->statusMessage;
				$this->incrementStat('numApiErrors');
			}else{
				$result['success'] = true;
				$result['message'] = 'Your hold was cancelled successfully';
				$this->incrementStat('numHoldsCancelled');
				$patron->clearCachedAccountSummaryForSource('axis360');
				$patron->forceReloadOfHolds();
			}
		}else{
			$result['message'] = 'Unable to connect to Axis 360';
		}
		return $result;
	}

	public function getAccountSummary(User $user) : AccountSummary
	{
		list($existingId, $summary) = $user->getCachedAccountSummary('axis360');

		if ($summary === null) {
			//Get account information from api
			require_once ROOT_DIR . '/sys/User/AccountSummary.php';
			$summary = new AccountSummary();
			$summary->userId = $user->id;
			$summary->source = 'axis360';
			$summary->resetCounters();

			if ($this->getAxis360AccessToken($user)) {
				require_once ROOT_DIR . '/RecordDrivers/Axis360RecordDriver.php';
				$settings = $this->getSettings($user);
				$checkoutsUrl = $settings->apiUrl . "/Services/VendorAPI/availability/v3_1";
				$params = [
					'patronId' => $user->getBarcode()
				];
				$headers = [
					'Authorization: ' . $this->accessToken,
					'Library: ' . $settings->libraryPrefix,
				];
				$this->initCurlWrapper();
				$this->curlWrapper->addCustomHeaders($headers, false);
				$response = $this->curlWrapper->curlPostPage($checkoutsUrl, $params);
				/** @var stdClass $xmlResults */
				$xmlResults = simplexml_load_string($response);
				$status = $xmlResults->status;
				if ($status->code == '0000') {
					foreach ($xmlResults->title as $title) {
						$availability = $title->availability;
						if ((string)$availability->isCheckedout == 'true') {
							$summary->numCheckedOut++;
						} elseif ((string)$availability->isInHoldQueue == 'true') {
							if ((string)$availability->isReserved == 'true') {
								$summary->numAvailableHolds++;
							} else {
								$summary->numUnavailableHolds++;
							}
						}
					}
				} else {
					$this->incrementStat('numApiErrors');
				}
			}

			$summary->lastLoaded = time();
			if ($existingId != null) {
				$summary->id = $existingId;
				$summary->update();
			}else{
				$summary->insert();
			}
		}

		return $summary;
	}

	/**
	 * @param User $patron
	 * @param string $titleId
	 *
	 * @param bool $fromRenew
	 * @return array
	 */
	public function checkOutTitle($patron, $titleId, $fromRenew = false)
	{
		$result = ['success' => false, 'message' => 'Unknown error'];

		if ($this->getAxis360AccessToken($patron)){
			$settings = $this->getSettings($patron);
			$params = [
				'titleId' => $titleId,
				'patronId' => $patron->getBarcode()
			];
			$checkoutUrl = $settings->apiUrl . "/Services/VendorAPI/checkout/v2";
			$headers = [
				'Authorization: ' . $this->accessToken,
				'Library: ' . $settings->libraryPrefix,
			];
			$this->initCurlWrapper();
			$this->curlWrapper->addCustomHeaders($headers, false);
			$response = $this->curlWrapper->curlPostPage($checkoutUrl, $params);
			/** @var stdClass $xmlResults */
			$xmlResults = simplexml_load_string($response);
			$checkoutResult = $xmlResults->checkoutResult;
			$status = $checkoutResult->status;
			if ($status->code != '0000') {
				$result['message'] = translate('Sorry, we could not checkout this title to you.');
				if ($status->code == '3113'){
					$result['noCopies'] = true;
					$result['message'] .= "\r\n\r\n" . translate('Would you like to place a hold instead?');
				}else{
					$result['message'] .= (string)$status->statusMessage;
					$this->incrementStat('numApiErrors');
				}
			} else {
				$result['success'] = true;
				$result['message'] = translate(['text' => 'axis360_checkout_success', 'defaultText' => 'Your title was checked out successfully. You may now download the title from your Account.']);
				if ($fromRenew) {
					$this->incrementStat('numRenewals');
				}else{
					$this->incrementStat('numCheckouts');
					$this->trackUserUsageOfAxis360($patron);
					$this->trackRecordCheckout($titleId);
					$patron->lastReadingHistoryUpdate = 0;
					$patron->update();
				}
				$patron->clearCachedAccountSummaryForSource('axis360');
				$patron->forceReloadOfCheckouts();
			}
		}else{
			$result['message'] = 'Unable to connect to Axis 360';
		}
		return $result;
	}

	private function getSettings(User $user = null){
		require_once ROOT_DIR . '/sys/Axis360/Axis360Scope.php';
		require_once ROOT_DIR . '/sys/Axis360/Axis360Setting.php';
		$activeLibrary = null;
		if ($user != null){
			$activeLibrary = $user->getHomeLibrary();
		}
		if ($activeLibrary == null){
			global $library;
			$activeLibrary = $library;
		}
		$scope = new Axis360Scope();
		$scope->id = $activeLibrary->axis360ScopeId;
		if ($activeLibrary->axis360ScopeId > 0) {
			if ($scope->find(true)) {
				$settings = new Axis360Setting();
				$settings->id = $scope->settingId;
				if ($settings->find(true)) {
					return $settings;
				} else {
					return false;
				}
			} else {
				return false;
			}
		}else{
			return false;
		}
	}

	/**
	 * @param $user
	 */
	public function trackUserUsageOfAxis360($user): void
	{
		require_once ROOT_DIR . '/sys/Axis360/UserAxis360Usage.php';
		$userUsage = new UserAxis360Usage();
		/** @noinspection DuplicatedCode */
		$userUsage->userId = $user->id;
		$userUsage->year = date('Y');
		$userUsage->month = date('n');
		$userUsage->instance = $_SERVER['SERVER_NAME'];

		if ($userUsage->find(true)) {
			$userUsage->usageCount++;
			$userUsage->update();
		} else {
			$userUsage->usageCount = 1;
			$userUsage->insert();
		}
	}

	/**
	 * @param string $recordId
	 */
	function trackRecordCheckout($recordId): void
	{
		require_once ROOT_DIR . '/sys/Axis360/Axis360RecordUsage.php';
		require_once ROOT_DIR . '/sys/Axis360/Axis360Title.php';
		$recordUsage = new Axis360RecordUsage();
		$product = new Axis360Title();
		$product->axis360Id = $recordId;
		if ($product->find(true)) {
			$recordUsage->axis360Id = $product->axis360Id;
			$recordUsage->instance = $_SERVER['SERVER_NAME'];
			$recordUsage->year = date('Y');
			$recordUsage->month = date('n');
			if ($recordUsage->find(true)) {
				$recordUsage->timesCheckedOut++;
				$recordUsage->update();
			} else {
				$recordUsage->timesCheckedOut = 1;
				$recordUsage->timesHeld = 0;
				$recordUsage->insert();
			}
		}
	}

	/**
	 * @param string $recordId
	 */
	function trackRecordHold($recordId): void
	{
		require_once ROOT_DIR . '/sys/Axis360/Axis360RecordUsage.php';
		require_once ROOT_DIR . '/sys/Axis360/Axis360Title.php';
		$recordUsage = new Axis360RecordUsage();
		$product = new Axis360Title();
		$product->axis360Id = $recordId;
		if ($product->find(true)){
			$recordUsage->instance = $_SERVER['SERVER_NAME'];
			$recordUsage->axis360Id = $product->axis360Id;
			$recordUsage->year = date('Y');
			$recordUsage->month = date('n');
			if ($recordUsage->find(true)) {
				$recordUsage->timesHeld++;
				$recordUsage->update();
			} else {
				$recordUsage->timesCheckedOut = 0;
				$recordUsage->timesHeld = 1;
				$recordUsage->insert();
			}
		}
	}

	/** @noinspection PhpUndefinedFieldInspection */
	private function loadHoldInfo(SimpleXMLElement $rawHold, array &$holds, User $user, $forSummary) : Hold
	{
		$hold = new Hold();
		$hold->type = 'axis360';
		$hold->source = 'axis360';

		$available = (string)$rawHold->isAvailable == 'Y';
		$titleId = (string)$rawHold->titleID;
		$hold->sourceId = $titleId;
		$hold->recordId = $titleId;
		$hold->title = (string)$rawHold->bookTitle;
		$hold->holdQueueLength = (string)$rawHold->totalHoldSize;
		$hold->position = (string)$rawHold->holdPosition;
		$hold->available = $available;
		if (!$available){
			$hold->canFreeze = true;
			$hold->frozen = (string)$rawHold->isSuspendHold == 'R';
			if ($hold->frozen){
				$hold->status = "Frozen";
			}
		}else{
			$hold->expirationDate = strtotime($rawHold->reservedEndDate);
		}

		require_once ROOT_DIR . '/RecordDrivers/Axis360RecordDriver.php';
		$axis360Record = new Axis360RecordDriver($titleId);
		if ($axis360Record->isValid()) {
			$hold->updateFromRecordDriver($axis360Record);
		}

		$hold->userId = $user->id;
		$key = $hold->source . $hold->sourceId . $hold->userId;
		if ($available){
			$holds['available'][$key] = $hold;
		}else{
			$holds['unavailable'][$key] = $hold;
		}
		return $hold;
	}

	/** @noinspection PhpUndefinedFieldInspection */
	private function loadCheckoutInfo(SimpleXMLElement $title, &$checkouts, User $user)
	{
		$checkout = new Checkout();
		$checkout->type = 'axis360';
		$checkout->source = 'axis360';
		$checkout->sourceId = (string)$title->titleId;
		$checkout->recordId = (string)$title->titleId;

		//After a title is returned, Axis 360 will still return it for a bit, but mark it as not checked out.
		if ((string)$title->availability->isCheckedout == 'N'){
			return;
		}
		$checkout->canRenew = (string)$title->availability->IsButtonRenew != 'N';
		$expirationDate = new DateTime($title->availability->checkoutEndDate);
		$checkout->dueDate = $expirationDate->getTimestamp();
		$checkout->accessOnlineUrl = (string)$title->titleUrl;
		$checkout->transactionId = (string)$title->availability->transactionID;
		require_once ROOT_DIR . '/RecordDrivers/Axis360RecordDriver.php';

		$axis360Record = new Axis360RecordDriver((string)$title->titleId);
		if ($axis360Record->isValid()) {
			$checkout->updateFromRecordDriver($axis360Record);
		}
		$checkout->userId = $user->id;

		$key = $checkout->source . $checkout->sourceId . $checkout->userId;
		$checkouts[$key] = $checkout;
	}

	function freezeHold(User $patron, $recordId)
	{
		$result = ['success' => false, 'message' => 'Unknown error'];
		if ($this->getAxis360AccessToken($patron)){
			$settings = $this->getSettings($patron);
			$freezeHoldUrl = $settings->apiUrl . "/Services/VendorAPI/suspendHold/v2/$recordId/{$patron->getBarcode()}";
			$headers = [
				'Authorization: ' . $this->accessToken,
				'Library: ' . $settings->libraryPrefix,
			];
			$this->initCurlWrapper();
			$this->curlWrapper->addCustomHeaders($headers, false);
			$response = $this->curlWrapper->curlSendPage($freezeHoldUrl, 'GET');
			/** @var stdClass $xmlResults */
			$xmlResults = simplexml_load_string($response);
			$freezeHoldResult = $xmlResults->HoldResult;
			$status = $freezeHoldResult->status;
			if ($status->code != '0000'){
				$result['message'] = "Could not freeze hold, " . (string)$status->statusMessage;
				$this->incrementStat('numApiErrors');
			}else{
				$result['success'] = true;
				$result['message'] = 'Your hold was frozen successfully';
				$this->incrementStat('numHoldsFrozen');
				$patron->forceReloadOfHolds();
			}
		}else{
			$result['message'] = 'Unable to connect to Axis 360';
		}
		return $result;
	}

	function thawHold(User $patron, $recordId)
	{
		$result = ['success' => false, 'message' => 'Unknown error'];
		if ($this->getAxis360AccessToken($patron)){
			$settings = $this->getSettings($patron);
			$freezeHoldUrl = $settings->apiUrl . "/Services/VendorAPI/activateHold/v2/$recordId/{$patron->getBarcode()}";
			$headers = [
				'Authorization: ' . $this->accessToken,
				'Library: ' . $settings->libraryPrefix,
			];
			$this->initCurlWrapper();
			$this->curlWrapper->addCustomHeaders($headers, false);
			$response = $this->curlWrapper->curlSendPage($freezeHoldUrl, 'GET');
			/** @var stdClass $xmlResults */
			$xmlResults = simplexml_load_string($response);
			$thawHoldResult = $xmlResults->HoldResult;
			$status = $thawHoldResult->status;
			if ($status->code != '0000'){
				$result['message'] = "Could not thaw hold, " . (string)$status->statusMessage;
				$this->incrementStat('numApiErrors');
			}else{
				$result['success'] = true;
				$result['message'] = 'Your hold was thawed successfully';
				$this->incrementStat('numHoldsThawed');
				$patron->forceReloadOfHolds();
			}
		}else{
			$result['message'] = 'Unable to connect to Axis 360';
		}
		return $result;
	}

	private function incrementStat(string $fieldName)
	{
		require_once ROOT_DIR . '/sys/Axis360/Axis360Stats.php';
		$axis360Stats = new Axis360Stats();
		$axis360Stats->instance = $_SERVER['SERVER_NAME'];
		$axis360Stats->year = date('Y');
		$axis360Stats->month = date('n');
		if ($axis360Stats->find(true)) {
			$axis360Stats->$fieldName++;
			$axis360Stats->update();
		} else {
			$axis360Stats->$fieldName = 1;
			$axis360Stats->insert();
		}
	}
}