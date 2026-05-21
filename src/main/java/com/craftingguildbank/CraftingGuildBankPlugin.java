package com.craftingguildbank;

import com.google.inject.Provides;
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
import net.runelite.api.widgets.Widget;
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

	private static final int CAPE_TELEPORT_ANIMATION = 714;
	private static final int GHOST_TIMEOUT_TICKS = 5;
	private static final int CRAFTING_GUILD_ARRIVAL_RADIUS = 2;

	private static final WorldPoint CRAFTING_GUILD_TELEPORT_TILE = new WorldPoint(2931, 3286, 0);
	private static final WorldPoint BANK_CHEST_TILE = CRAFTING_GUILD_TELEPORT_TILE.dx(5).dy(-6);

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

	private int ghostStartedTick;
	private int teleportClickedTick;
	private boolean pendingCraftingGuildTeleport;
	private boolean pendingTeleportAnimationCheck;

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
		final String widgetName = getWidgetName(event);

		if (isCraftingGuildTeleport(option, widgetName))
		{
			startPendingTeleportCheck();
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

		if (pendingTeleportAnimationCheck && client.getTickCount() > teleportClickedTick)
		{
			pendingTeleportAnimationCheck = false;

			if (player.getAnimation() != CAPE_TELEPORT_ANIMATION)
			{
				clearGhost();
				pendingCraftingGuildTeleport = false;

				if (isWithinCraftingGuildArrivalRadius(playerLocation))
				{
					realBankChest = findRealBankChest();
				}

				return;
			}

			startGhostHighlight(player);
		}

		if (ghostBankChestLocalLocation != null)
		{
			handleGhostHighlight(playerLocation);
		}

		if (pendingCraftingGuildTeleport && playerLocation.equals(CRAFTING_GUILD_TELEPORT_TILE))
		{
			showRealBankChestAndClearGhost();
		}

		if (realBankChest != null && !isWithinCraftingGuildArrivalRadius(playerLocation))
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

	private void startPendingTeleportCheck()
	{
		if (ghostBankChestModel == null)
		{
			clientThread.invokeLater(this::loadGhostBankChestModel);
		}

		clearGhost();

		teleportClickedTick = client.getTickCount();
		pendingTeleportAnimationCheck = true;
		pendingCraftingGuildTeleport = false;
		realBankChest = null;
	}

	private void startGhostHighlight(Player player)
	{
		if (player == null)
		{
			return;
		}

		if (ghostBankChestModel == null)
		{
			loadGhostBankChestModel();
		}

		if (ghostBankChestModel == null)
		{
			return;
		}

		final WorldView worldView = client.getTopLevelWorldView();
		final LocalPoint playerLocal = player.getLocalLocation();

		if (worldView == null || playerLocal == null)
		{
			return;
		}

		// Animation 714 has started, so this local position should be the player's
		// stopped teleport-animation position rather than the moving/running position.
		ghostBankChestLocalLocation = predictedBankChestLocal(playerLocal, worldView);

		// Keep the preview level with the player's teleport-animation position,
		// not the predicted chest tile.
		ghostProjectionZ = Perspective.getTileHeight(client, playerLocal, worldView.getPlane());

		ghostStartedTick = client.getTickCount();
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
		if (isWithinCraftingGuildArrivalRadius(playerLocation))
		{
			showRealBankChestAndClearGhost();
			return;
		}

		if (client.getTickCount() - ghostStartedTick >= GHOST_TIMEOUT_TICKS)
		{
			clearGhost();
			pendingCraftingGuildTeleport = false;
			pendingTeleportAnimationCheck = false;
		}
	}

	private void showRealBankChestAndClearGhost()
	{
		realBankChest = findRealBankChest();
		clearGhost();

		pendingCraftingGuildTeleport = false;
		pendingTeleportAnimationCheck = false;
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

	private boolean isCraftingGuildTeleport(String option, String widgetName)
	{
		if (option.equals("teleport") && isCraftingCapeWidget(widgetName))
		{
			return true;
		}

		return option.equals("crafting guild") && widgetName.equals("max cape");
	}

	private boolean isCraftingCapeWidget(String widgetName)
	{
		return widgetName.equals("crafting cape")
				|| widgetName.equals("crafting cape(t)");
	}

	private String getWidgetName(MenuOptionClicked event)
	{
		final Widget widget = event.getWidget();

		if (widget == null)
		{
			return "";
		}

		return clean(widget.getName());
	}

	private boolean isWithinCraftingGuildArrivalRadius(WorldPoint worldPoint)
	{
		return worldPoint.getPlane() == CRAFTING_GUILD_TELEPORT_TILE.getPlane()
				&& worldPoint.distanceTo2D(CRAFTING_GUILD_TELEPORT_TILE) <= CRAFTING_GUILD_ARRIVAL_RADIUS;
	}

	private void clearGhost()
	{
		ghostBankChestLocalLocation = null;
		ghostProjectionZ = 0;
		ghostStartedTick = 0;
	}

	private void resetAllState()
	{
		clearGhost();
		realBankChest = null;
		teleportClickedTick = 0;
		pendingCraftingGuildTeleport = false;
		pendingTeleportAnimationCheck = false;
	}

	private static String clean(String text)
	{
		return Text.removeTags(text == null ? "" : text)
				.toLowerCase()
				.trim();
	}
}