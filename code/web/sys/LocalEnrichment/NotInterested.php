<?php

class NotInterested extends DataObject{
	public $id;
	public $userId;
	public $groupedRecordPermanentId;
	public $dateMarked;

	public $__table = 'user_not_interested';

	public function getUniquenessFields(): array
	{
		return ['userId', 'groupedRecordPermanentId'];
	}

	public function okToExport(array $selectedFilters): bool
	{
		$okToExport = parent::okToExport($selectedFilters);
		$user = new User();
		$user->id = $this->userId;
		if ($user->find(true)) {
			if ($user->homeLocationId == 0 || array_key_exists($user->homeLocationId, $selectedFilters['locations'])) {
				$okToExport = true;
			}
		}
		return $okToExport;
	}

	public function toArray($includeRuntimeProperties = true, $encryptFields = false): array
	{
		$return =  parent::toArray($includeRuntimeProperties, $encryptFields);
		unset($return['userId']);
		return $return;
	}

	public function getLinksForJSON(): array
	{
		$links = parent::getLinksForJSON();
		$user = new User();
		$user->id = $this->userId;
		if ($user->find(true)) {
			$links['user'] = $user->username;
		}
		return $links;
	}

	public function loadEmbeddedLinksFromJSON($jsonData, $mappings, $overrideExisting = 'keepExisting')
	{
		parent::loadEmbeddedLinksFromJSON($jsonData, $mappings, $overrideExisting);
		if (isset($jsonData['user'])){
			$username = $jsonData['user'];
			if (array_key_exists($username, $mappings['users'])){
				$username = $mappings['users'][$username];
			}
			$user = new User();
			$user->username = $username;
			if ($user->find(true)){
				$this->userId = $user->id;
			}
		}
	}
}