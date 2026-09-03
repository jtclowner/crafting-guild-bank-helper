package com.craftingguildbank;

import com.google.inject.Provides;
import java.util.concurrent.ThreadLocalRandom;
import javax.inject.Inject;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
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
import net.runelite.api.gameval.SpotanimID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.audio.AudioPlayer;
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
@Slf4j
public class CraftingGuildBankPlugin extends Plugin
{
	static final int BANK_CHEST_OBJECT_ID = 14886;

	private static final int BANK_CHEST_MODEL_ID = 21969;
	private static final int BANK_CHEST_ORIENTATION = 512;

	private static final int CAPE_TELEPORT_ANIMATION = 714;
	private static final int GHOST_TIMEOUT_TICKS = 5;
	private static final int MOUNTED_CAPE_INTERACTION_TIMEOUT_TICKS = 20;
	private static final int MOUNTED_MAX_CAPE_PERKS_TIMEOUT_TICKS = 50;
	private static final int CRAFTING_GUILD_ARRIVAL_RADIUS = 2;
	private static final int CREDITS_SPOT_ANIM_KEY = 0x48454944;
	private static final int CREDITS_OVERHEAD_CYCLES = 180;

	private static final String[] CREDITS_MESSAGES = {
		"Wow, thanks Heidi!",
		"What a cool plugin idea!",
		"Heidi, you're a genius!",
		"This one’s for you, Heidi!",
		"Heidi made banking better!",
		"Another great idea from Heidi!",
		"The Crafting Guild thanks you, Heidi!",
		"Banking in style - thanks Heidi!"
	};

	private static final String[] CREDITS_JINGLES = {
		"jingles/agility.wav",
		"jingles/crafting.wav",
		"jingles/ranged.wav",
		"jingles/slayer.wav",
		"jingles/woodcutting.wav"
	};

	private static final WorldPoint CRAFTING_GUILD_TELEPORT_TILE = new WorldPoint(2931, 3286, 0);
	private static final WorldPoint BANK_CHEST_TILE = CRAFTING_GUILD_TELEPORT_TILE.dx(5).dy(-6);

	@Inject
	private Client client;

	@Inject
	private ClientThread clientThread;

	@Inject
	private OverlayManager overlayManager;

	@Inject
	private AudioPlayer audioPlayer;

	@Inject
	private CraftingGuildBankOverlay overlay;

	@Inject
	private CraftingGuildBankConfig config;

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
	private int mountedMaxCapePerksClickedTick;
	private boolean pendingCraftingGuildTeleport;
	private boolean pendingTeleportAnimationCheck;
	private boolean allowDelayedTeleportAnimation;
	private boolean pendingMountedMaxCapePerks;

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
		final String target = clean(event.getMenuTarget());

		if (isMountedMaxCapePerks(option, target))
		{
			mountedMaxCapePerksClickedTick = client.getTickCount();
			pendingMountedMaxCapePerks = true;
		}

		if (isCraftingGuildTeleport(option, widgetName, target, pendingMountedMaxCapePerks))
		{
			pendingMountedMaxCapePerks = false;
			startPendingTeleportCheck(isMountedCraftingCape(target));
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

		if (pendingMountedMaxCapePerks
				&& client.getTickCount() - mountedMaxCapePerksClickedTick >= MOUNTED_MAX_CAPE_PERKS_TIMEOUT_TICKS)
		{
			pendingMountedMaxCapePerks = false;
		}

		if (pendingTeleportAnimationCheck && client.getTickCount() > teleportClickedTick)
		{
			if (player.getAnimation() != CAPE_TELEPORT_ANIMATION)
			{
				if (allowDelayedTeleportAnimation
						&& client.getTickCount() - teleportClickedTick < MOUNTED_CAPE_INTERACTION_TIMEOUT_TICKS)
				{
					if (isWithinCraftingGuildArrivalRadius(playerLocation))
					{
						showRealBankChestAndClearGhost();
						return;
					}

					return;
				}

				clearGhost();
				pendingCraftingGuildTeleport = false;
				pendingTeleportAnimationCheck = false;
				allowDelayedTeleportAnimation = false;

				if (isWithinCraftingGuildArrivalRadius(playerLocation))
				{
					realBankChest = findRealBankChest();
				}

				return;
			}

			pendingTeleportAnimationCheck = false;
			allowDelayedTeleportAnimation = false;
			startGhostHighlight(player);
		}

		if (ghostBankChestLocalLocation != null)
		{
			handleGhostHighlight(playerLocation);
		}

		// Only celebrate once the teleport has completed and the player has arrived.
		if (pendingCraftingGuildTeleport && isWithinCraftingGuildArrivalRadius(playerLocation))
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

	private void startPendingTeleportCheck(boolean allowDelayedAnimation)
	{
		if (ghostBankChestModel == null)
		{
			clientThread.invokeLater(this::loadGhostBankChestModel);
		}

		clearGhost();

		teleportClickedTick = client.getTickCount();
		pendingTeleportAnimationCheck = true;
		allowDelayedTeleportAnimation = allowDelayedAnimation;
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
		playCreditsCelebration();

		pendingCraftingGuildTeleport = false;
		pendingTeleportAnimationCheck = false;
		allowDelayedTeleportAnimation = false;
	}

	private void playCreditsCelebration()
	{
		if (!config.creditsMode())
		{
			return;
		}

		final Player player = client.getLocalPlayer();

		if (player == null)
		{
			return;
		}

		final ThreadLocalRandom random = ThreadLocalRandom.current();
		player.createSpotAnim(CREDITS_SPOT_ANIM_KEY, SpotanimID.LEVELUP_MAX, 0, 0);
		player.setOverheadText(CREDITS_MESSAGES[random.nextInt(CREDITS_MESSAGES.length)]);
		player.setOverheadCycle(CREDITS_OVERHEAD_CYCLES);

		final int soundEffectVolume = client.getPreferences().getSoundEffectVolume();

		if (soundEffectVolume > 0)
		{
			final float gain = 20f * (float) Math.log10(soundEffectVolume / 127f);

			try
			{
				audioPlayer.play(
					CraftingGuildBankPlugin.class,
					CREDITS_JINGLES[random.nextInt(CREDITS_JINGLES.length)],
					gain
				);
			}
			catch (Exception ex)
			{
				log.warn("Unable to play Credits mode jingle", ex);
			}
		}
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

	private static boolean isCraftingGuildTeleport(
		String option,
		String widgetName,
		String target,
		boolean fromMountedMaxCapePerks
	)
	{
		if (option.equals("teleport")
				&& (isCraftingCapeWidget(widgetName) || isMountedCraftingCape(target)))
		{
			return true;
		}

		return option.equals("crafting guild")
				&& (isMaxCape(widgetName) || isMaxCape(target) || fromMountedMaxCapePerks);
	}

	private static boolean isMountedMaxCapePerks(String option, String target)
	{
		return option.equals("perks") && target.equals("mounted max cape");
	}

	private static boolean isCraftingCapeWidget(String widgetName)
	{
		return widgetName.equals("crafting cape")
				|| widgetName.equals("crafting cape(t)");
	}

	private static boolean isMountedCraftingCape(String target)
	{
		return target.equals("mounted crafting cape")
				|| target.equals("mounted crafting cape(t)")
				|| target.equals("mounted crafting cape (t)");
	}

	private static boolean isMaxCape(String name)
	{
		return name.equals("max cape") || name.equals("mounted max cape");
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
		mountedMaxCapePerksClickedTick = 0;
		pendingCraftingGuildTeleport = false;
		pendingTeleportAnimationCheck = false;
		allowDelayedTeleportAnimation = false;
		pendingMountedMaxCapePerks = false;
	}

	private static String clean(String text)
	{
		return Text.removeTags(text == null ? "" : text)
				.toLowerCase()
				.trim();
	}
}
