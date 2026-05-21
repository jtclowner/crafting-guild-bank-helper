package com.craftingguildbank;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.Shape;
import javax.inject.Inject;
import net.runelite.api.Client;
import net.runelite.api.GameObject;
import net.runelite.api.Model;
import net.runelite.api.Perspective;
import net.runelite.api.WorldView;
import net.runelite.api.coords.LocalPoint;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;

public class CraftingGuildBankOverlay extends Overlay
{
	private final Client client;
	private final CraftingGuildBankPlugin plugin;
	private final CraftingGuildBankConfig config;

	@Inject
	public CraftingGuildBankOverlay(
		Client client,
		CraftingGuildBankPlugin plugin,
		CraftingGuildBankConfig config
	)
	{
		this.client = client;
		this.plugin = plugin;
		this.config = config;

		setPosition(OverlayPosition.DYNAMIC);
		setLayer(OverlayLayer.ABOVE_SCENE);
	}

	@Override
	public Dimension render(Graphics2D graphics)
	{
		renderGhostBankChest(graphics);
		renderRealBankChest(graphics);

		return null;
	}

	private void renderGhostBankChest(Graphics2D graphics)
	{
		final LocalPoint ghostLocal = plugin.getGhostBankChestLocalLocation();

		if (ghostLocal == null)
		{
			return;
		}

		final Model model = plugin.getGhostBankChestModel();

		if (model == null)
		{
			return;
		}

		final WorldView worldView = client.getTopLevelWorldView();

		if (worldView == null)
		{
			return;
		}

		final Shape clickbox = Perspective.getClickbox(
			client,
			worldView,
			model,
			plugin.getGhostBankChestOrientation(),
			ghostLocal.getX(),
			ghostLocal.getY(),
			plugin.getGhostProjectionZ()
		);

		if (clickbox != null)
		{
			renderShape(graphics, clickbox);
		}
	}

	private void renderRealBankChest(Graphics2D graphics)
	{
		final GameObject bankChest = plugin.getRealBankChest();

		if (bankChest == null)
		{
			return;
		}

		Shape clickbox = bankChest.getClickbox();

		if (clickbox == null)
		{
			clickbox = bankChest.getCanvasTilePoly();
		}

		if (clickbox != null)
		{
			renderShape(graphics, clickbox);
		}
	}

	private void renderShape(Graphics2D graphics, Shape shape)
	{
		final Color outline = config.highlightColor();

		if (config.fillHighlight())
		{
			final Color fill = new Color(
				outline.getRed(),
				outline.getGreen(),
				outline.getBlue(),
				config.fillOpacity()
			);

			graphics.setColor(fill);
			graphics.fill(shape);
		}

		graphics.setColor(outline);
		graphics.setStroke(new BasicStroke(config.outlineWidth()));
		graphics.draw(shape);
	}
}
