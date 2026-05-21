package com.craftingguildbank;

import com.google.inject.Provides;
import java.util.Set;
import javax.inject.Inject;
import lombok.Getter;
import net.runelite.api.Client;
import net.runelite.api.GameObject;
import net.runelite.api.Model;
import net.runelite.api.Perspective;
import net.runelite.api.Player;
import net.runelite.api.Scene;
import net.runelite.api.Tile;
import net.runelite.api.WorldView;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.MenuOptionClicked;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.overlay.OverlayManager;
import net.runelite.client.util.Text;

@PluginDescriptor(
	name = "Crafting Guild Bank Helper",
	description = "Shows where to click the Crafting Guild bank chest after cape teleporting",
	tags = {"crafting", "guild", "bank", "cape", "teleport", "highlight"}
)
public class CraftingGuildBankPlugin extends Plugin
{
	static final int BANK_CHEST_OBJECT_ID = 14886;

	private static final int BANK_CHEST_MODEL_ID = 21969;
	private static final int BANK_CHEST_ORIENTATION = 512;

	// Item IDs
	private static final int CRAFTING_CAPE = 9780;
	private static final int CRAFTING_CAPE_T = 9781;
	private static final int MAX_CAPE_INVENTORY = 13280;
	private static final int MAX_CAPE_WORN = 13342;
	
	private static final WorldPoint CRAFTING_GUILD_TELEPORT_TILE = new WorldPoint(2931, 3286, 0);
	private static final WorldPoint BANK_CHEST_TILE = CRAFTING_GUILD_TELEPORT_TILE.dx(5).dy(-6);
	private static final long GHOST_TIMEOUT_MILLIS = 5000L;

	private static final Set<String> BANK_CHEST_OPTIONS = Set.of("use", "collect", "bank", "open");

	@Inject
	private Client client;

	@Inject
	private ClientThread clientThread;

	@Inject
	private OverlayManager overlayManager;

	@Inject
	private CraftingGuildBankOverlay overlay;

	@Getter
	private LocalPoint ghostBankChestLocalLocation;

	@Getter
	private int ghostProjectionZ;

	@Getter
	private GameObject realBankChest;

	@Getter
	private Model ghostBankChestModel;

	private long ghostStartedAtMillis;
	private boolean pendingCraftingGuildTeleport;

	@Override
	protected void startUp()
	{
		overlayManager.add(overlay);
		resetAllState();

		ghostBankChestModel = null;
		clientThread.invokeLater(this::loadGhostBankChestModel);
	}

	@Override
	protected void shutDown()
	{
		overlayManager.remove(overlay);
		resetAllState();

		ghostBankChestModel = null;
	}

	@Provides
	CraftingGuildBankConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(CraftingGuildBankConfig.class);
	}

	int getGhostBankChestOrientation()
	{
		return BANK_CHEST_ORIENTATION;
	}

	@Subscribe
	public void onMenuOptionClicked(MenuOptionClicked event)
	{
		final String option = clean(event.getMenuOption());
		final String target = clean(event.getMenuTarget());
		final int itemId = event.getItemId();

		if (isCraftingGuildTeleport(option, itemId))
		{
			startGhostHighlight();
			return;
		}

		if (realBankChest != null && isBankChestClick(option, target, event.getId()))
		{
			realBankChest = null;
		}
	}

	@Subscribe
	public void onGameTick(GameTick event)
	{
		final Player player = client.getLocalPlayer();

		if (player == null)
		{
			resetAllState();
			return;
		}

		final WorldPoint playerLocation = player.getWorldLocation();

		if (ghostBankChestLocalLocation != null)
		{
			handleGhostHighlight(playerLocation);
		}

		if (pendingCraftingGuildTeleport && playerLocation.equals(CRAFTING_GUILD_TELEPORT_TILE))
		{
			applyRealBankChestHighlight();
		}

		if (realBankChest != null && !playerLocation.equals(CRAFTING_GUILD_TELEPORT_TILE))
		{
			realBankChest = null;
		}
	}

	private void loadGhostBankChestModel()
	{
		if (ghostBankChestModel == null)
		{
			ghostBankChestModel = client.loadModel(BANK_CHEST_MODEL_ID);
		}
	}

	private void startGhostHighlight()
	{
		final Player player = client.getLocalPlayer();

		if (player == null)
		{
			return;
		}

		if (ghostBankChestModel == null)
		{
			clientThread.invokeLater(this::loadGhostBankChestModel);
		}

		final WorldView worldView = client.getTopLevelWorldView();
		final LocalPoint playerLocal = player.getLocalLocation();

		if (worldView == null || playerLocal == null)
		{
			return;
		}

		ghostBankChestLocalLocation = predictedBankChestLocal(playerLocal, worldView);

		// Keep the preview level with the player while the teleport animation is still playing.
		ghostProjectionZ = Perspective.getTileHeight(client, playerLocal, worldView.getPlane());

		ghostStartedAtMillis = System.currentTimeMillis();
		pendingCraftingGuildTeleport = true;
		realBankChest = null;
	}

	private LocalPoint predictedBankChestLocal(LocalPoint playerLocal, WorldView worldView)
	{
		return new LocalPoint(
			playerLocal.getX() + (Perspective.LOCAL_TILE_SIZE * 5),
			playerLocal.getY() - (Perspective.LOCAL_TILE_SIZE * 6),
			worldView
		);
	}

	private void handleGhostHighlight(WorldPoint playerLocation)
	{
		if (playerLocation.equals(CRAFTING_GUILD_TELEPORT_TILE))
		{
			return;
		}

		if (System.currentTimeMillis() - ghostStartedAtMillis >= GHOST_TIMEOUT_MILLIS)
		{
			clearGhost();
			pendingCraftingGuildTeleport = false;
		}
	}

	private void applyRealBankChestHighlight()
	{
		final GameObject bankChest = findRealBankChest();

		if (bankChest == null)
		{
			return;
		}

		realBankChest = bankChest;
		clearGhost();
		pendingCraftingGuildTeleport = false;
	}

	private GameObject findRealBankChest()
	{
		final WorldView worldView = client.getTopLevelWorldView();

		if (worldView == null)
		{
			return null;
		}

		final Scene scene = worldView.getScene();

		if (scene == null)
		{
			return null;
		}

		final LocalPoint localPoint = LocalPoint.fromWorld(worldView, BANK_CHEST_TILE);

		if (localPoint == null)
		{
			return null;
		}

		final int sceneX = localPoint.getSceneX();
		final int sceneY = localPoint.getSceneY();

		if (sceneX < 0 || sceneX >= 104 || sceneY < 0 || sceneY >= 104)
		{
			return null;
		}

		final Tile tile = scene.getTiles()[worldView.getPlane()][sceneX][sceneY];

		if (tile == null || tile.getGameObjects() == null)
		{
			return null;
		}

		for (GameObject gameObject : tile.getGameObjects())
		{
			if (gameObject != null && gameObject.getId() == BANK_CHEST_OBJECT_ID)
			{
				return gameObject;
			}
		}

		return null;
	}

	private boolean isCraftingGuildTeleport(String option, int itemId)
	{
		return option.equals("teleport") && isCraftingCape(itemId)
			|| option.equals("crafting guild") && isDefaultMaxCape(itemId);
	}

	private boolean isCraftingCape(int itemId)
	{
		return itemId == CRAFTING_CAPE
			|| itemId == CRAFTING_CAPE_T;
	}

	private boolean isDefaultMaxCape(int itemId)
	{
		return itemId == MAX_CAPE_INVENTORY
			|| itemId == MAX_CAPE_WORN;
	}

	private boolean isBankChestClick(String option, String target, int id)
	{
		if (!BANK_CHEST_OPTIONS.contains(option))
		{
			return false;
		}

		return id == BANK_CHEST_OBJECT_ID
			|| target.contains("bank chest");
	}

	private void clearGhost()
	{
		ghostBankChestLocalLocation = null;
		ghostProjectionZ = 0;
	}

	private void resetAllState()
	{
		clearGhost();
		realBankChest = null;
		ghostStartedAtMillis = 0;
		pendingCraftingGuildTeleport = false;
	}

	private static String clean(String text)
	{
		return Text.removeTags(text == null ? "" : text)
			.toLowerCase()
			.trim();
	}
}
