package inventorysetups;

import com.google.common.hash.HashFunction;
import com.google.common.hash.Hashing;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.reflect.TypeToken;
import inventorysetups.serialization.InventorySetupItemSerializable;
import inventorysetups.serialization.InventorySetupItemSerializableTypeAdapter;
import inventorysetups.serialization.InventorySetupSerializable;
import inventorysetups.serialization.LongTypeAdapter;
import inventorysetups.ui.InventorySetupsPluginPanel;
import joptsimple.internal.Strings;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.config.ConfigManager;

import javax.inject.Inject;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static inventorysetups.InventorySetupsPlugin.CONFIG_GROUP;

@Slf4j
public class InventorySetupsPersistentDataManager
{

	private final InventorySetupsPlugin plugin;
	private final InventorySetupsPluginPanel panel;
	private final ConfigManager configManager;
	private final InventorySetupsCache cache;

	private Gson gson;

	private final List<InventorySetup> inventorySetups;
	private final List<InventorySetupsSection> sections;


	// Fast non-cryptographic hash with relatively short output. Could use murmur3_32_fixed instead; but being extra cautious of collisions.
	public static final HashFunction hashFunction = Hashing.murmur3_128();

	public static final String CONFIG_KEY_SETUPS_MIGRATED_V2 = "migratedV2";
	public static final String CONFIG_KEY_SETUPS_MIGRATED_V3 = "migratedV3";
	public static final String CONFIG_KEY_SETUPS = "setups";
	public static final String CONFIG_KEY_SETUPS_V2 = "setupsV2";
	public static final String CONFIG_KEY_SETUPS_V3_PREFIX = "setupsV3_";
	public static final String CONFIG_KEY_SETUPS_ORDER_V3 = "setupsOrderV3_";
	public static final String CONFIG_KEY_SECTIONS = "sections";

	@Inject
	public InventorySetupsPersistentDataManager(final InventorySetupsPlugin plugin,
												final InventorySetupsPluginPanel panel,
												final ConfigManager manager,
												final InventorySetupsCache cache,
												final Gson gson,
												final List<InventorySetup> inventorySetups,
												final List<InventorySetupsSection> sections)
	{
		this.plugin = plugin;
		this.panel = panel;
		this.configManager = manager;
		this.cache = cache;
		this.gson = gson;
		this.inventorySetups = inventorySetups;
		this.sections = sections;

		this.gson = this.gson.newBuilder().registerTypeAdapter(long.class, new LongTypeAdapter()).create();
		this.gson = this.gson.newBuilder().registerTypeAdapter(InventorySetupItemSerializable.class, new InventorySetupItemSerializableTypeAdapter()).create();
	}

	public void loadConfig()
	{
		inventorySetups.clear();
		sections.clear();
		cache.clearAll();

		// Handles migration of old setup data
		handleMigrationOfOldData();

		final List<InventorySetup> setupsFromConfig = loadV3Setups();
		inventorySetups.addAll(setupsFromConfig);
		processSetupsFromConfig();

		Type sectionType = new TypeToken<ArrayList<InventorySetupsSection>>()
		{

		}.getType();
		sections.addAll(loadData(CONFIG_KEY_SECTIONS, sectionType));
		for (final InventorySetupsSection section : sections)
		{
			// Remove any duplicates that exist
			List<String> uniqueSetups = section.getSetups().stream().distinct().collect(Collectors.toList());
			section.setSetups(uniqueSetups);

			// Remove setups which don't exist in a section
			section.getSetups().removeIf(s -> !cache.getInventorySetupNames().containsKey(s));
			cache.addSection(section);
		}

	}

	public void updateConfig(boolean updateSetups, boolean updateSections)
	{
		if (updateSetups)
		{
			// Rather than escaping the name to pick a config key, instead use hash of the name. Benefits:
			//   Changes to the escaping function or supported characters in the name string don't affect config keys.
			//   Standardizes length; preventing overflow issues from extremely long names
			//   Keeps json as the single source of truth for names; other plugins won't try to grab names from config keys.
			final String wholePrefix = ConfigManager.getWholeKey(CONFIG_GROUP, null, CONFIG_KEY_SETUPS_V3_PREFIX);
			final List<String> oldSetupKeys = configManager.getConfigurationKeys(wholePrefix);
			Set<String> oldSetupHashes = oldSetupKeys.stream().map(key -> key.substring(wholePrefix.length())).collect(Collectors.toSet());

			final List<String> setupsOrder = new ArrayList<>();
			for (final InventorySetup setup : inventorySetups)
			{
				final String hash = hashFunction.hashUnencodedChars(setup.getName()).toString();
				setupsOrder.add(hash);
				oldSetupHashes.remove(hash);
				final String data = gson.toJson(InventorySetupSerializable.convertFromInventorySetup(setup));
				configManager.setConfiguration(CONFIG_GROUP, CONFIG_KEY_SETUPS_V3_PREFIX + hash, data);
			}

			for (final String removedSetupHash : oldSetupHashes)
			{
				// Any hashes still in the oldSetupHashes set were for setups that were either renamed (and saved to a new hash above) or deleted.
				configManager.unsetConfiguration(CONFIG_GROUP, CONFIG_KEY_SETUPS_V3_PREFIX + removedSetupHash);
			}

			final String setupsOrderJson = gson.toJson(setupsOrder);
			configManager.setConfiguration(CONFIG_GROUP, CONFIG_KEY_SETUPS_ORDER_V3, setupsOrderJson);
		}

		if (updateSections)
		{
			final String jsonSections = gson.toJson(sections);
			configManager.setConfiguration(CONFIG_GROUP, CONFIG_KEY_SECTIONS, jsonSections);
		}

	}

	private <T> List<T> loadData(final String configKey, Type type)
	{
		final String storedData = configManager.getConfiguration(CONFIG_GROUP, configKey);
		if (Strings.isNullOrEmpty(storedData))
		{
			return new ArrayList<>();
		}
		else
		{
			try
			{
				// serialize the internal data structure from the json in the configuration
				return gson.fromJson(storedData, type);
			}
			catch (Exception e)
			{
				log.error("Exception occurred while loading data", e);
				return new ArrayList<>();
			}
		}
	}

	private List<InventorySetup> loadV1Setups()
	{
		Type setupTypeV1 = new TypeToken<ArrayList<InventorySetup>>()
		{

		}.getType();
		return loadData(CONFIG_KEY_SETUPS, setupTypeV1);
	}

	private List<InventorySetup> loadV2Setups()
	{
		Type setupTypeV2 = new TypeToken<ArrayList<InventorySetupSerializable>>()
		{

		}.getType();
		List<InventorySetupSerializable> issList = new ArrayList<>(loadData(CONFIG_KEY_SETUPS_V2, setupTypeV2));
		List<InventorySetup> isList = new ArrayList<>();
		for (final InventorySetupSerializable iss : issList)
		{
			isList.add(InventorySetupSerializable.convertToInventorySetup(iss));
		}
		return isList;
	}

	private InventorySetup loadV3Setup(String configKey)
	{
		final String storedData = configManager.getConfiguration(CONFIG_GROUP, configKey);
		try
		{
			return InventorySetupSerializable.convertToInventorySetup(gson.fromJson(storedData, InventorySetupSerializable.class));
		}
		catch (Exception e)
		{
			log.error(String.format("Exception occurred while loading %s", configKey), e);
			throw e;
		}
	}

	private List<InventorySetup> loadV3Setups()
	{
		final String wholePrefix = ConfigManager.getWholeKey(CONFIG_GROUP, null, CONFIG_KEY_SETUPS_V3_PREFIX);
		final List<String> loadedSetupWholeKeys = configManager.getConfigurationKeys(wholePrefix);
		Set<String> loadedSetupKeys = loadedSetupWholeKeys.stream().map(
			key -> key.substring(wholePrefix.length() - CONFIG_KEY_SETUPS_V3_PREFIX.length())
		).collect(Collectors.toSet());

		Type setupsOrderType = new TypeToken<ArrayList<String>>()
		{

		}.getType();
		final String setupsOrderJson = configManager.getConfiguration(CONFIG_GROUP, CONFIG_KEY_SETUPS_ORDER_V3);
		List<String> setupsOrder = gson.fromJson(setupsOrderJson, setupsOrderType);
		if (setupsOrder == null)
		{
			setupsOrder = new ArrayList<>();
		}

		List<InventorySetup> loadedSetups = new ArrayList<>();
		for (final String configHash : setupsOrder)
		{
			final String configKey = CONFIG_KEY_SETUPS_V3_PREFIX + configHash;
			if (loadedSetupKeys.remove(configKey))
			{ // Handles if hash is present only in configOrder.
				final InventorySetup setup = loadV3Setup(configKey);
				loadedSetups.add(setup);
			}
		}
		for (final String configKey : loadedSetupKeys)
		{
			// Load any remaining setups not present in setupsOrder. Useful if updateConfig crashes midway.
			log.info("Loading setup that was missing from Order key: " + configKey);
			final InventorySetup setup = loadV3Setup(configKey);
			loadedSetups.add(setup);
		}
		return loadedSetups;
	}

	private void processSetupsFromConfig()
	{
		for (final InventorySetup setup : inventorySetups)
		{
			final List<InventorySetupsItem> potentialRunePouch = plugin.getAmmoHandler().getRunePouchDataIfInContainer(setup.getInventory());
			if (setup.getRune_pouch() == null && potentialRunePouch != null)
			{
				setup.updateRunePouch(potentialRunePouch);
			}
			final List<InventorySetupsItem> potentialBoltPouch = plugin.getAmmoHandler().getBoltPouchDataIfInContainer(setup.getInventory());
			if (setup.getBoltPouch() == null && potentialBoltPouch != null)
			{
				setup.updateBoltPouch(potentialBoltPouch);
			}
			final List<InventorySetupsItem> potentialQuiver = plugin.getAmmoHandler().getQuiverDataIfInSetup(setup.getInventory(), setup.getEquipment());
			if (setup.getQuiver() == null && potentialQuiver != null)
			{
				setup.updateQuiver(potentialQuiver);
			}
			if (setup.getNotes() == null)
			{
				setup.updateNotes("");
			}
			if (setup.getAdditionalFilteredItems() == null)
			{
				setup.updateAdditionalItems(new HashMap<>());
			}

			cache.addSetup(setup);

			// add Item names
			addItemNames(setup.getInventory());
			addItemNames(setup.getEquipment());
			addItemNames(setup.getRune_pouch());
			addItemNames(setup.getBoltPouch());
			for (final Integer key : setup.getAdditionalFilteredItems().keySet())
			{
				addItemName(setup.getAdditionalFilteredItems().get(key));
			}

		}

	}

	private void addItemNames(final List<InventorySetupsItem> items)
	{
		if (items != null)
		{
			for (final InventorySetupsItem item : items)
			{
				addItemName(item);
			}
		}
	}

	private void addItemName(final InventorySetupsItem item)
	{
		item.setName(plugin.getItemManager().getItemComposition(item.getId()).getName());
	}

	private void handleMigrationOfOldData()
	{
		String hasMigratedToV2 = configManager.getConfiguration(CONFIG_GROUP, CONFIG_KEY_SETUPS_MIGRATED_V2);
		if (Strings.isNullOrEmpty(hasMigratedToV2))
		{
			log.info("Migrating data from V1 to V3");
			inventorySetups.addAll(loadV1Setups());
			updateConfig(true, false);
			inventorySetups.clear();
			configManager.setConfiguration(CONFIG_GROUP, CONFIG_KEY_SETUPS_MIGRATED_V2, "True");
			configManager.setConfiguration(CONFIG_GROUP, CONFIG_KEY_SETUPS_MIGRATED_V3, "True");
		}

		String oldV1Data = configManager.getConfiguration(CONFIG_GROUP, CONFIG_KEY_SETUPS);
		if (oldV1Data != null)
		{
			log.info("Removing old v1 data key");
			configManager.unsetConfiguration(CONFIG_GROUP, CONFIG_KEY_SETUPS);
		}

		String hasMigratedToV3 = configManager.getConfiguration(CONFIG_GROUP, CONFIG_KEY_SETUPS_MIGRATED_V3);
		if (Strings.isNullOrEmpty(hasMigratedToV3))
		{
			log.info("Migrating data from V2 to V3");
			inventorySetups.addAll(loadV2Setups());
			updateConfig(true, false);
			inventorySetups.clear();
			configManager.setConfiguration(CONFIG_GROUP, CONFIG_KEY_SETUPS_MIGRATED_V3, "True");
		}

		// TODO Don't unset configuration until new version is stable
//		String oldV2Data = configManager.getConfiguration(CONFIG_GROUP, CONFIG_KEY_SETUPS_V2);
//		if (oldV2Data != null)
//		{
//			log.info("Removing old v2 data key");
//			configManager.unsetConfiguration(CONFIG_GROUP, CONFIG_KEY_SETUPS_V2);
//		}
	}

	private String fixOldJSONData(final String json)
	{
		JsonElement je = this.gson.fromJson(json, JsonElement.class);
		JsonArray ja = je.getAsJsonArray();
		for (JsonElement elem : ja)
		{
			JsonObject setup = elem.getAsJsonObject();

			// Example if needed in the future
//			if (setup.getAsJsonPrimitive("stackDifference").isBoolean())
//			{
//				int stackDiff = setup.get("stackDifference").getAsBoolean() ? 1 : 0;
//				setup.remove("stackDifference");
//				setup.addProperty("stackDifference", stackDiff);
//			}
		}
		return je.toString();
	}


}
