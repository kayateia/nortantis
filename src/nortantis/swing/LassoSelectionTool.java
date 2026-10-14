package nortantis.swing;

import nortantis.MapSettings;
import nortantis.MapText;
import nortantis.PathOperations;
import nortantis.WorldGraph;
import nortantis.editor.CenterEdit;
import nortantis.editor.FreeIcon;
import nortantis.editor.MapUpdater;
import nortantis.editor.River;
import nortantis.editor.RiverPathNode;
import nortantis.editor.Road;
import nortantis.editor.RoadPathNode;
import nortantis.geom.Point;
import nortantis.geom.Rectangle;
import nortantis.geom.RotatedRectangle;
import nortantis.graph.voronoi.Center;
import nortantis.platform.DrawQuality;
import nortantis.platform.Image;
import nortantis.platform.Painter;
import nortantis.swing.translation.Translation;
import nortantis.util.Assets;

import javax.swing.*;
import java.awt.*;
import java.awt.event.KeyEvent;
import java.awt.event.MouseEvent;
import java.awt.geom.Area;
import java.awt.geom.Path2D;
import java.nio.file.Paths;
import java.util.*;
import java.util.List;

/**
 * Selects everything inside an area the user draws freehand - land and water polygons, icons, and text - then lets the user move, copy,
 * cut, paste, or delete the selection.
 *
 * <p>
 * Which object types the lasso picks up is controlled by checkboxes in the tool options panel, so a user can lasso a landmass without
 * dragging along every tree and label on it (or the reverse).
 *
 * <p>
 * Object positions are stored in different coordinate spaces and this tool has to work in one consistently: the graph space that
 * {@link EditorTool#getPointOnGraph} returns and that the map's highlights are drawn in. Cell locations ({@link Center#loc}), icon content
 * bounds, and the text drawer's stamped bounds are already in graph space at the displayed resolution; only a {@link FreeIcon}'s or
 * {@link MapText}'s stored location is resolution invariant and has to be scaled by {@link MainWindow#displayQualityScale} to compare and
 * move it.
 *
 * <p>
 * A move is a translation of what was selected. For icons and text that is a literal offset. For terrain it is a remap: the selected cells'
 * land/water/lake/region state is copied to whichever cell now sits under each offset location, and the cells it left behind become open
 * ocean. Because the cell grid is fixed, a moved coastline is re-derived from the new cell states, which is why dragging land reshapes the
 * coast rather than sliding the original outline.
 */
public class LassoSelectionTool extends EditorTool
{
	private static final int minimumPointsForALasso = 3;
	/**
	 * How far the cursor must travel, in graph pixels, before another point is appended to the lasso path. Without this a slow drag piles up
	 * hundreds of near-duplicate points.
	 */
	private static final double lassoPointSpacing = 2.0;

	private JCheckBox landAndWaterCheckbox;
	private JCheckBox iconsCheckbox;
	private JCheckBox textCheckbox;
	private JCheckBox roadsAndRiversCheckbox;
	private JButton cutButton;
	private JButton deselectAllButton;

	/** The lasso outline, in graph coordinates: the in-progress path while drawing, then the closed path of the finished selection. */
	private final List<Point> lassoPath = new ArrayList<>();
	private boolean isDrawingLasso;

	private boolean isMovingSelection;
	private Point moveStartGraph;
	/** The delta last used to build the drop preview, so a drag that hasn't crossed a whole graph pixel doesn't rebuild it. */
	private Point lastPreviewDelta;
	/** Copies of the selected texts taken when a move began, so the incremental text draw erases the text where it used to be. */
	private final Map<MapText, MapText> textPositionsBeforeMove = new IdentityHashMap<>();

	private final Set<Center> selectedCenters = new LinkedHashSet<>();
	private final Set<FreeIcon> selectedIcons = new LinkedHashSet<>();
	private final List<MapText> selectedTexts = new ArrayList<>();
	// Roads and rivers are matched by identity rather than by value: both override equals on their mutable node lists, so a value-based set
	// would lose track of an entry the moment its nodes were reassigned.
	private final Set<Road> selectedRoads = Collections.newSetFromMap(new IdentityHashMap<>());
	private final Set<River> selectedRivers = Collections.newSetFromMap(new IdentityHashMap<>());

	private final List<CopiedCell> copiedCells = new ArrayList<>();
	private final List<FreeIcon> copiedIcons = new ArrayList<>();
	private final List<MapText> copiedTexts = new ArrayList<>();
	private final List<Road> copiedRoads = new ArrayList<>();
	private final List<River> copiedRivers = new ArrayList<>();
	/** Where the clipboard's contents were centered when copied, in graph coordinates. */
	private Point clipboardOrigin;

	/**
	 * A copied cell's terrain state, and where the cell sat when it was copied (graph coordinates). Icons and trees are carried along in
	 * case the cell has any still stored as center edits rather than as free icons.
	 */
	record CopiedCell(double graphX, double graphY, boolean isWater, boolean isLake, Integer regionId, nortantis.editor.CenterIcon icon, nortantis.editor.CenterTrees trees)
	{
	}

	public LassoSelectionTool(MainWindow parent, ToolsPanel toolsPanel, MapUpdater mapUpdater)
	{
		super(parent, toolsPanel, mapUpdater);
	}

	@Override
	public String getToolbarName()
	{
		return Translation.get("lassoSelectionTool.name");
	}

	@Override
	public int getMnemonic()
	{
		return KeyEvent.VK_L;
	}

	@Override
	public Image getToolIcon()
	{
		Image icon = Assets.readImage(Paths.get(Assets.getAssetsPath(), "internal/Lasso tool.png").toString());
		try (Painter p = icon.createPainter(DrawQuality.High))
		{
			String text = Translation.get("lassoSelectionTool.toolIcon");
			p.setColor(nortantis.platform.Color.black);
			p.setFont(createToolIconFont(19, text));
			p.drawString(text, 12 + getXOffSetBasedOnLanguage(), 50);
		}
		return icon;
	}

	private int getXOffSetBasedOnLanguage()
	{
		return switch (Translation.getEffectiveLocale().getLanguage())
		{
			case "de" -> -3;
			case "ru" -> -4;
			case "pt" -> -3;
			default -> 0;
		};
	}

	@Override
	protected JPanel createToolOptionsPanel()
	{
		GridBagOrganizer organizer = new GridBagOrganizer();

		JPanel toolOptionsPanel = organizer.panel;
		toolOptionsPanel.setBorder(BorderFactory.createEmptyBorder(3, 3, 3, 3));

		{
			organizer.addLeftAlignedComponent(new JLabel("<html>" + Translation.get("lassoSelectionTool.instructions") + "</html>"));
		}

		{
			landAndWaterCheckbox = new JCheckBox(Translation.get("lassoSelectionTool.selectLandAndWater"));
			landAndWaterCheckbox.setToolTipText(Translation.get("lassoSelectionTool.selectLandAndWater.tooltip"));
			landAndWaterCheckbox.setSelected(true);
			landAndWaterCheckbox.addActionListener(e ->
			{
				if (!landAndWaterCheckbox.isSelected())
				{
					selectedCenters.clear();
				}
				updateSelectionOverlay();
			});
			organizer.addLeftAlignedComponent(landAndWaterCheckbox);
		}
		{
			iconsCheckbox = new JCheckBox(Translation.get("lassoSelectionTool.selectIcons"));
			iconsCheckbox.setToolTipText(Translation.get("lassoSelectionTool.selectIcons.tooltip"));
			iconsCheckbox.setSelected(true);
			iconsCheckbox.addActionListener(e ->
			{
				if (!iconsCheckbox.isSelected())
				{
					selectedIcons.clear();
				}
				updateSelectionOverlay();
			});
			organizer.addLeftAlignedComponent(iconsCheckbox);
		}
		{
			textCheckbox = new JCheckBox(Translation.get("lassoSelectionTool.selectText"));
			textCheckbox.setToolTipText(Translation.get("lassoSelectionTool.selectText.tooltip"));
			textCheckbox.setSelected(true);
			textCheckbox.addActionListener(e ->
			{
				if (!textCheckbox.isSelected())
				{
					selectedTexts.clear();
				}
				updateSelectionOverlay();
			});
			organizer.addLeftAlignedComponent(textCheckbox);
		}
		{
			roadsAndRiversCheckbox = new JCheckBox(Translation.get("lassoSelectionTool.selectRoadsAndRivers"));
			roadsAndRiversCheckbox.setToolTipText(Translation.get("lassoSelectionTool.selectRoadsAndRivers.tooltip"));
			roadsAndRiversCheckbox.setSelected(true);
			roadsAndRiversCheckbox.addActionListener(e ->
			{
				if (!roadsAndRiversCheckbox.isSelected())
				{
					selectedRoads.clear();
					selectedRivers.clear();
				}
				updateSelectionOverlay();
			});
			organizer.addLeftAlignedComponent(roadsAndRiversCheckbox);
		}

		organizer.addSeparator();

		{
			cutButton = new JButton(Translation.get("lassoSelectionTool.cut"));
			cutButton.setToolTipText(Translation.get("lassoSelectionTool.cut.tooltip", SwingHelper.getCommandKeyName()));
			cutButton.addActionListener(e -> cutSelection());
			SwingHelper.bindButtonShortcut(cutButton, KeyStroke.getKeyStroke(KeyEvent.VK_X, SwingHelper.getMenuShortcutKeyMask()), "lassoCutAction");

			deselectAllButton = new JButton(Translation.get("lassoSelectionTool.deselectAll"));
			deselectAllButton.setToolTipText(Translation.get("lassoSelectionTool.deselectAll.tooltip"));
			deselectAllButton.addActionListener(e -> clearSelection());

			EditClipboardButtonsWidget editButtons = new EditClipboardButtonsWidget("lassoSelectionTool", this::copySelection, this::pasteClipboard, this::deleteSelection,
					Translation.get("lassoSelectionTool.delete.tooltip"));
			editButtons.addToOrganizer(organizer, Arrays.asList(cutButton, deselectAllButton));
		}

		organizer.addHorizontalSpacerRowToHelpComponentAlignment(0.666);
		organizer.addVerticalFillerRow();

		registerEscapeKeyHandler();

		return toolOptionsPanel;
	}

	/**
	 * Binds Escape to cancel an in-progress lasso, or to clear the selection when there isn't one. Uses a key event dispatcher rather than an
	 * input map because Escape must also work when focus is in another component, such as a text field in the theme panel.
	 */
	private void registerEscapeKeyHandler()
	{
		KeyboardFocusManager.getCurrentKeyboardFocusManager().addKeyEventDispatcher(e ->
		{
			if (!isSelected() || e.getID() != KeyEvent.KEY_PRESSED || e.getKeyCode() != KeyEvent.VK_ESCAPE)
			{
				return false;
			}
			// An open dialog (including a modal one) owns Escape, so leave it alone rather than clearing the selection behind it.
			if (KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusedWindow() instanceof JDialog)
			{
				return false;
			}

			if (isDrawingLasso)
			{
				cancelLasso();
				return true;
			}
			if (!isSelectionEmpty())
			{
				clearSelection();
				return true;
			}
			return false;
		});
	}

	private boolean isSelectionEmpty()
	{
		return selectedCenters.isEmpty() && selectedIcons.isEmpty() && selectedTexts.isEmpty() && selectedRoads.isEmpty() && selectedRivers.isEmpty();
	}

	@Override
	protected void handleMousePressedOnMap(MouseEvent e)
	{
		Point graphPoint = getPointOnGraph(e.getPoint());
		if (graphPoint == null)
		{
			return;
		}

		Rectangle selectionBounds = isSelectionEmpty() ? null : getSelectionBoundsGraph();
		if (selectionBounds != null && selectionBounds.contains(graphPoint))
		{
			beginMove(graphPoint);
			return;
		}

		beginLasso(graphPoint, SwingHelper.isCommandKeyDown(e));
	}

	@Override
	protected void handleMouseDraggedOnMap(MouseEvent e)
	{
		Point graphPoint = getPointOnGraph(e.getPoint());
		if (graphPoint == null)
		{
			return;
		}

		if (isDrawingLasso)
		{
			extendLasso(graphPoint);
		}
		else if (isMovingSelection)
		{
			updateMovePreview(graphPoint);
		}
	}

	@Override
	protected void handleMouseReleasedOnMap(MouseEvent e)
	{
		Point graphPoint = getPointOnGraph(e.getPoint());
		if (graphPoint == null)
		{
			graphPoint = lassoPath.isEmpty() ? null : lassoPath.get(lassoPath.size() - 1);
		}

		if (isDrawingLasso)
		{
			finishLasso(graphPoint);
		}
		else if (isMovingSelection)
		{
			finishMove(graphPoint);
		}
	}

	@Override
	protected void handleMouseMovedOnMap(MouseEvent e)
	{
	}

	@Override
	protected void handleMouseExitedMap(MouseEvent e)
	{
	}

	@Override
	protected void onAfterShowMap()
	{
		// A zoom or display-quality change repaints from scratch, so the selection and any in-progress drop preview have to be re-posted.
		if (isMovingSelection && mapEditingPanel.getMousePosition() != null)
		{
			updateMovePreview(getPointOnGraph(mapEditingPanel.getMousePosition()));
		}
		else
		{
			updateSelectionOverlay();
		}
	}

	@Override
	public void onSwitchingTo()
	{
		super.onSwitchingTo();
		updater.doWhenMapIsReadyForInteractions(() ->
		{
			if (isSelected())
			{
				updateSelectionOverlay();
			}
		});
	}

	@Override
	public void onSwitchingAway()
	{
		cancelLasso();
		clearSelection();
	}

	@Override
	protected void onAfterUndoRedo()
	{
		// Undo and redo replace the edit collections with deep copies, so every selected object would be left pointing at an instance that is
		// no longer part of the map. Drop the selection rather than trying to re-find the replacements.
		clearSelection();
	}

	@Override
	protected void onBeforeUndoRedo()
	{
		cancelLasso();
		isMovingSelection = false;
		moveStartGraph = null;
	}

	@Override
	public void loadSettingsIntoGUI(MapSettings settings, boolean isUndoRedoOrAutomaticChange, boolean refreshImagePreviews)
	{
		// This tool has no settings of its own: checkboxes here describe what the next lasso picks up, and the selection itself is not part
		// of a saved map.
		handleEnablingAndDisabling(settings);
	}

	@Override
	public void getSettingsFromGUI(MapSettings settings)
	{
	}

	@Override
	public void handleEnablingAndDisabling(MapSettings settings)
	{
		updateEditButtonStates();
	}

	@Override
	public void onBeforeLoadingNewMap()
	{
		clearSelection();
	}

	private void updateEditButtonStates()
	{
		boolean hasSelection = !isSelectionEmpty();
		if (cutButton != null)
		{
			cutButton.setEnabled(hasSelection);
		}
		if (deselectAllButton != null)
		{
			deselectAllButton.setEnabled(hasSelection);
		}
	}

	private void beginLasso(Point graphPoint, boolean addToSelection)
	{
		if (!addToSelection)
		{
			selectedCenters.clear();
			selectedIcons.clear();
			selectedTexts.clear();
			selectedRoads.clear();
			selectedRivers.clear();
		}
		lassoPath.clear();
		lassoPath.add(graphPoint);
		isDrawingLasso = true;
		mapEditingPanel.setLassoPath(lassoPath);
		updateSelectionOverlay();
	}

	private void extendLasso(Point graphPoint)
	{
		Point lastPoint = lassoPath.get(lassoPath.size() - 1);
		if (graphPoint.distanceTo(lastPoint) < lassoPointSpacing)
		{
			return;
		}
		lassoPath.add(graphPoint);
		mapEditingPanel.setLassoPath(lassoPath);
		mapEditingPanel.repaint();
	}

	private void finishLasso(Point graphPoint)
	{
		isDrawingLasso = false;
		if (graphPoint != null && !lassoPath.isEmpty() && graphPoint.distanceTo(lassoPath.get(lassoPath.size() - 1)) >= lassoPointSpacing)
		{
			lassoPath.add(graphPoint);
		}

		Area lassoArea = buildLassoArea();
		if (lassoArea == null)
		{
			cancelLasso();
			return;
		}

		if (landAndWaterCheckbox.isSelected())
		{
			selectedCenters.addAll(findCentersInArea(lassoArea));
		}
		if (iconsCheckbox.isSelected())
		{
			selectedIcons.addAll(findIconsInArea(lassoArea));
		}
		if (textCheckbox.isSelected())
		{
			for (MapText text : findTextsInArea(lassoArea))
			{
				if (!selectedTexts.contains(text))
				{
					selectedTexts.add(text);
				}
			}
		}
		if (roadsAndRiversCheckbox.isSelected())
		{
			selectedRoads.addAll(findRoadsInArea(lassoArea));
			selectedRivers.addAll(findRiversInArea(lassoArea));
		}

		updateSelectionOverlay();
	}

	private void cancelLasso()
	{
		isDrawingLasso = false;
		lassoPath.clear();
		mapEditingPanel.clearLassoPath();
		mapEditingPanel.repaint();
	}

	private Area buildLassoArea()
	{
		if (lassoPath.size() < minimumPointsForALasso)
		{
			return null;
		}

		Path2D.Double path = new Path2D.Double();
		path.moveTo(lassoPath.get(0).x, lassoPath.get(0).y);
		for (int i = 1; i < lassoPath.size(); i++)
		{
			path.lineTo(lassoPath.get(i).x, lassoPath.get(i).y);
		}
		path.closePath();
		return new Area(path);
	}

	/**
	 * Cells are picked up when their center falls inside the lasso, which keeps selection predictable for large or irregular polygons whose
	 * edges stick outside the drawn area.
	 */
	private List<Center> findCentersInArea(Area lassoArea)
	{
		List<Center> found = new ArrayList<>();
		if (updater.mapParts == null || updater.mapParts.graph == null)
		{
			return found;
		}
		for (Center center : updater.mapParts.graph.centers)
		{
			if (lassoArea.contains(center.loc.x, center.loc.y))
			{
				found.add(center);
			}
		}
		return found;
	}

	private List<FreeIcon> findIconsInArea(Area lassoArea)
	{
		List<FreeIcon> found = new ArrayList<>();
		if (updater.mapParts == null || updater.mapParts.iconDrawer == null)
		{
			return found;
		}
		mainWindow.edits.freeIcons.doWithLock(() ->
		{
			for (FreeIcon icon : mainWindow.edits.freeIcons)
			{
				Rectangle bounds = iconContentBounds(icon);
				if (bounds != null && lassoArea.contains(bounds.getCenter().x, bounds.getCenter().y))
				{
					found.add(icon);
				}
			}
		});
		return found;
	}

	private List<MapText> findTextsInArea(Area lassoArea)
	{
		List<MapText> found = new ArrayList<>();
		for (MapText text : mainWindow.edits.text)
		{
			if (text.value == null || text.value.isEmpty() || text.line1Bounds == null)
			{
				// Text whose bounds have not been stamped yet cannot be hit-tested; the next draw fills them in.
				continue;
			}
			Point center = text.line1Bounds.getBounds().getCenter();
			if (lassoArea.contains(center.x, center.y))
			{
				found.add(text);
			}
		}
		return found;
	}

	/**
	 * A road or river counts as inside the lasso when any of its control points is, so that lassoing a landmass picks up the road running
	 * across it. Lines have no meaningful half-inside state - a road's whole path translates together - so the entire line comes along.
	 */
	private List<Road> findRoadsInArea(Area lassoArea)
	{
		List<Road> found = new ArrayList<>();
		for (Road road : mainWindow.edits.roads)
		{
			if (anyNodeInArea(lassoArea, road.nodes))
			{
				found.add(road);
			}
		}
		return found;
	}

	private List<River> findRiversInArea(Area lassoArea)
	{
		List<River> found = new ArrayList<>();
		for (River river : mainWindow.edits.rivers)
		{
			if (anyNodeInArea(lassoArea, river.nodes))
			{
				found.add(river);
			}
		}
		return found;
	}

	private boolean anyNodeInArea(Area lassoArea, List<? extends nortantis.editor.PathNode> nodes)
	{
		double resolutionScale = getResolutionScale();
		for (nortantis.editor.PathNode node : nodes)
		{
			Point graphPoint = graphPointForResolutionInvariantPoint(node.getLoc());
			if (lassoArea.contains(graphPoint.x, graphPoint.y))
			{
				return true;
			}
		}
		// A path whose control points all fall outside the lasso can still cross it, so test the segments between them too.
		List<Point> locations = PathOperations.toLocationList(nodes);
		if (locations.size() < 2)
		{
			return false;
		}
		Path2D.Double path = new Path2D.Double();
		path.moveTo(locations.get(0).x * resolutionScale, locations.get(0).y * resolutionScale);
		for (int i = 1; i < locations.size(); i++)
		{
			path.lineTo(locations.get(i).x * resolutionScale, locations.get(i).y * resolutionScale);
		}
		Area lineArea = new Area(new BasicStroke((float) (resolutionScale * 2)).createStrokedShape(path));
		lineArea.intersect(lassoArea);
		return !lineArea.isEmpty();
	}

	private Rectangle iconContentBounds(FreeIcon icon)
	{
		if (updater.mapParts == null || updater.mapParts.iconDrawer == null)
		{
			return null;
		}
		nortantis.IconDrawTask task = updater.mapParts.iconDrawer.toIconDrawTask(icon);
		return task == null ? null : task.getOrCreateContentBoundsPadded();
	}

	private List<Rectangle> iconBoundsOf(Collection<FreeIcon> icons)
	{
		List<Rectangle> bounds = new ArrayList<>();
		for (FreeIcon icon : icons)
		{
			Rectangle iconBounds = iconContentBounds(icon);
			if (iconBounds != null)
			{
				bounds.add(iconBounds);
			}
		}
		return bounds;
	}

	private List<RotatedRectangle> textBoundsOf(Collection<MapText> texts)
	{
		List<RotatedRectangle> bounds = new ArrayList<>();
		for (MapText text : texts)
		{
			if (text.line1Bounds != null)
			{
				bounds.add(text.line1Bounds);
			}
			if (text.line2Bounds != null)
			{
				bounds.add(text.line2Bounds);
			}
		}
		return bounds;
	}

	private void updateSelectionOverlay()
	{
		mapEditingPanel.setLassoSelection(selectedCenters, iconBoundsOf(selectedIcons), textBoundsOf(selectedTexts), polylinesOf(selectedRoads, selectedRivers, null));
		updateEditButtonStates();
		mapEditingPanel.repaint();
	}

	private void clearSelection()
	{
		selectedCenters.clear();
		selectedIcons.clear();
		selectedTexts.clear();
		selectedRoads.clear();
		selectedRivers.clear();
		lassoPath.clear();
		mapEditingPanel.clearLassoOverlays();
		updateEditButtonStates();
		mapEditingPanel.repaint();
	}

	private Rectangle getSelectionBoundsGraph()
	{
		Rectangle bounds = null;
		if (!selectedCenters.isEmpty() && updater.mapParts != null && updater.mapParts.graph != null)
		{
			bounds = WorldGraph.getBoundingBox(selectedCenters);
		}
		for (Rectangle iconBounds : iconBoundsOf(selectedIcons))
		{
			bounds = bounds == null ? iconBounds : bounds.add(iconBounds);
		}
		for (RotatedRectangle textBounds : textBoundsOf(selectedTexts))
		{
			Rectangle unrotated = textBounds.getBounds();
			bounds = bounds == null ? unrotated : bounds.add(unrotated);
		}
		for (List<Point> polyline : polylinesOf(selectedRoads, selectedRivers, null))
		{
			for (Point point : polyline)
			{
				bounds = bounds == null ? new Rectangle(point.x, point.y, 0, 0) : bounds.add(point);
			}
		}
		return bounds;
	}

	/**
	 * The given roads' and rivers' control-point paths in graph coordinates, optionally offset by {@code deltaGraph} so a drop preview can
	 * show where the lines would end up.
	 */
	private List<List<Point>> polylinesOf(Collection<Road> roads, Collection<River> rivers, Point deltaGraph)
	{
		List<List<Point>> polylines = new ArrayList<>();
		double resolutionScale = getResolutionScale();
		for (Road road : roads)
		{
			polylines.add(toGraphPolyline(PathOperations.toLocationList(road.nodes), resolutionScale, deltaGraph));
		}
		for (River river : rivers)
		{
			polylines.add(toGraphPolyline(PathOperations.toLocationList(river.nodes), resolutionScale, deltaGraph));
		}
		return polylines;
	}

	private static List<Point> toGraphPolyline(List<Point> locationsResolutionInvariant, double resolutionScale, Point deltaGraph)
	{
		double deltaX = deltaGraph == null ? 0 : deltaGraph.x;
		double deltaY = deltaGraph == null ? 0 : deltaGraph.y;
		List<Point> polyline = new ArrayList<>(locationsResolutionInvariant.size());
		for (Point location : locationsResolutionInvariant)
		{
			polyline.add(new Point(location.x * resolutionScale + deltaX, location.y * resolutionScale + deltaY));
		}
		return polyline;
	}

	private double getResolutionScale()
	{
		return mainWindow.displayQualityScale;
	}

	private Point graphPointForResolutionInvariantPoint(Point resolutionInvariantPoint)
	{
		return resolutionInvariantPoint.mult(getResolutionScale());
	}

	// ---------------------------------------------------------------------------------------------------
	// Move
	// ---------------------------------------------------------------------------------------------------

	private void beginMove(Point graphPoint)
	{
		isMovingSelection = true;
		moveStartGraph = graphPoint;
		lastPreviewDelta = null;
		textPositionsBeforeMove.clear();
		for (MapText text : selectedTexts)
		{
			textPositionsBeforeMove.put(text, text.deepCopy());
		}
	}

	private void updateMovePreview(Point graphPoint)
	{
		if (!isMovingSelection || moveStartGraph == null)
		{
			return;
		}

		Point delta = new Point(Math.round(graphPoint.x - moveStartGraph.x), Math.round(graphPoint.y - moveStartGraph.y));
		if (delta.equals(lastPreviewDelta))
		{
			return;
		}
		lastPreviewDelta = delta;

		Map<Center, Center> destinationToSource = computeMoveMapping(delta);

		Set<Center> ghostLandCenters = new HashSet<>();
		Set<Center> ghostOceanCenters = new HashSet<>();
		Set<Center> ghostLakeCenters = new HashSet<>();

		for (Map.Entry<Center, Center> entry : destinationToSource.entrySet())
		{
			Center source = entry.getValue();
			CenterEdit edit = mainWindow.edits.centerEdits.get(source.index);
			boolean isWater = edit != null ? edit.isWater : source.isWater;
			boolean isLake = edit != null ? edit.isLake : source.isLake;
			if (!isWater)
			{
				ghostLandCenters.add(entry.getKey());
			}
			else if (isLake)
			{
				ghostLakeCenters.add(entry.getKey());
			}
			else
			{
				ghostOceanCenters.add(entry.getKey());
			}
		}

		// Cells the selection is moving off of become open ocean, so the preview shows what is about to be given up as well as what is about to
		// be gained.
		for (Center source : selectedCenters)
		{
			if (!destinationToSource.containsKey(source))
			{
				ghostOceanCenters.add(source);
			}
		}

		List<Rectangle> iconBounds = new ArrayList<>();
		for (Rectangle iconBoundsBefore : iconBoundsOf(selectedIcons))
		{
			iconBounds.add(iconBoundsBefore.translate(delta.x, delta.y));
		}

		List<RotatedRectangle> textBounds = new ArrayList<>();
		for (RotatedRectangle textBoundsBefore : textBoundsOf(selectedTexts))
		{
			// These bounds are already in graph space, like the icon bounds above, so they shift by the same delta the cells do.
			textBounds.add(textBoundsBefore.translate(delta));
		}

		mapEditingPanel.setLassoPreview(ghostLandCenters, ghostOceanCenters, ghostLakeCenters, iconBounds, textBounds,
				polylinesOf(selectedRoads, selectedRivers, delta));
		// The lasso outline travels with the preview too, so the whole selection reads as moving rather than the contents sliding out from
		// under a stationary outline. The stored path is left where the user drew it; only what is drawn moves.
		mapEditingPanel.setLassoPath(translatedLassoPath(delta));
		mapEditingPanel.repaint();
	}

	private List<Point> translatedLassoPath(Point delta)
	{
		List<Point> translated = new ArrayList<>(lassoPath.size());
		for (Point point : lassoPath)
		{
			translated.add(new Point(point.x + delta.x, point.y + delta.y));
		}
		return translated;
	}

	private void finishMove(Point graphPoint)
	{
		if (!isMovingSelection)
		{
			return;
		}
		isMovingSelection = false;
		Point delta = null;
		if (graphPoint != null && moveStartGraph != null)
		{
			delta = new Point(Math.round(graphPoint.x - moveStartGraph.x), Math.round(graphPoint.y - moveStartGraph.y));
		}
		else if (lastPreviewDelta != null)
		{
			// The drag ended off the map. Apply wherever the preview last showed rather than discarding the move.
			delta = lastPreviewDelta;
		}
		if (delta != null && (delta.x != 0 || delta.y != 0))
		{
			applyMove(delta);
		}
		else
		{
			// Nothing was committed, so put the outline back where the user drew it.
			mapEditingPanel.setLassoPath(lassoPath);
		}
		moveStartGraph = null;
		lastPreviewDelta = null;
		textPositionsBeforeMove.clear();
		updateSelectionOverlay();
	}

	/**
	 * Applies a translation of everything currently selected: terrain is remapped cell to cell, icons and text are offset. The undone cells
	 * are left as open ocean, so a moved landmass leaves water behind it.
	 */
	private void applyMove(Point delta)
	{
		Set<Center> changedCenters = moveTerrain(delta);

		// Collect the roads' and rivers' positions before and after, so the redraw covers the path the lines left behind as well as the path
		// they now take.
		List<Point> lineNodesBefore = selectedLineNodeLocations();
		moveRoadsAndRivers(delta);
		addCentersUnderLocations(changedCenters, lineNodesBefore);
		addCentersUnderLocations(changedCenters, selectedLineNodeLocations());

		List<FreeIcon> changedIcons = moveIcons(delta);
		List<MapText> changedTexts = moveTexts(delta);

		translateLassoPath(delta);

		undoer.setUndoPoint(UpdateType.Incremental, this);
		if (!changedCenters.isEmpty())
		{
			updater.createAndShowMapIncrementalUsingCenters(changedCenters);
		}
		if (!changedIcons.isEmpty())
		{
			updater.createAndShowMapIncrementalUsingIcons(changedIcons);
		}
		if (!changedTexts.isEmpty())
		{
			updater.createAndShowMapIncrementalUsingText(changedTexts);
		}
	}

	/**
	 * Slides the drawn lasso by the same offset as the objects it selected, so the outline keeps wrapping what the user moved instead of being
	 * left behind marking where the selection used to be.
	 */
	private void translateLassoPath(Point delta)
	{
		for (int i = 0; i < lassoPath.size(); i++)
		{
			Point point = lassoPath.get(i);
			lassoPath.set(i, new Point(point.x + delta.x, point.y + delta.y));
		}
		mapEditingPanel.setLassoPath(lassoPath);
	}

	private Set<Center> moveTerrain(Point delta)
	{
		Map<Center, Center> destinationToSource = computeMoveMapping(delta);
		if (destinationToSource.isEmpty())
		{
			return new HashSet<>();
		}
		Set<Center> changedCenters = applyTerrainMove(mainWindow.edits, selectedCenters, destinationToSource);

		// The selection travels with the terrain, so the next drag starts from where the land now is.
		selectedCenters.clear();
		selectedCenters.addAll(destinationToSource.keySet());
		return changedCenters;
	}

	/**
	 * Resolves the cells the current selection moves onto for a given offset.
	 */
	private Map<Center, Center> computeMoveMapping(Point delta)
	{
		if (updater.mapParts == null || updater.mapParts.graph == null || selectedCenters.isEmpty())
		{
			return Collections.emptyMap();
		}
		return mapMovedCells(selectedCenters, updater.mapParts.graph.centers, delta, updater.mapParts.graph.getMeanCenterWidth(), this::locateCell);
	}

	/**
	 * Works out which cells a selection moves onto, and the selected cell each destination takes its terrain from.
	 *
	 * <p>
	 * Each destination is resolved from the selection's side - a cell is part of the moved region when the location {@code delta} behind it
	 * falls inside the selection - rather than projecting every selected cell forward. Projecting forward loses cells: two selected cells can
	 * round to the same destination, and whichever cell nobody lands on is left as water, which is what punched holes in the middle of a moved
	 * landmass. Working backwards covers every cell of the translated region, so the moved terrain stays solid.
	 *
	 * @param candidatePadding
	 *            Slack, in graph pixels, on the region searched for destination cells, so a cell whose center sits just outside the
	 *            selection's shifted bounds is still considered.
	 * @param cellLocator
	 *            Resolves a graph-space point to the cell containing it, or returns null off the map. A parameter so the mapping can be
	 *            exercised without a generated world graph.
	 * @return Each destination cell mapped to the selected cell it takes its terrain from.
	 */
	static Map<Center, Center> mapMovedCells(Collection<Center> selectedCenters, Collection<Center> allCenters, Point delta, double candidatePadding,
			java.util.function.Function<Point, Center> cellLocator)
	{
		Map<Center, Center> destinationToSource = new LinkedHashMap<>();
		if (selectedCenters.isEmpty())
		{
			return destinationToSource;
		}

		Set<Integer> selectedIndexes = new HashSet<>();
		for (Center selected : selectedCenters)
		{
			selectedIndexes.add(selected.index);
		}

		Rectangle candidateBounds = WorldGraph.getBoundingBox(selectedCenters).translate(delta.x, delta.y).pad(candidatePadding, candidatePadding);
		for (Center candidate : allCenters)
		{
			if (!candidateBounds.contains(candidate.loc))
			{
				continue;
			}
			Center source = cellLocator.apply(new Point(candidate.loc.x - delta.x, candidate.loc.y - delta.y));
			if (source != null && selectedIndexes.contains(source.index))
			{
				destinationToSource.put(candidate, source);
			}
		}
		return destinationToSource;
	}

	/**
	 * Moves terrain by rewriting cell edits: the cells the selection left behind become open ocean, and each destination cell takes the
	 * land/water/lake/region state - and anything still stored as center edits - of the selected cell it came from. Because the polygon grid
	 * is fixed, the moved coastline is re-derived from the new cell states rather than the original outline being translated, which is what
	 * makes a dragged landmass reshape the coast.
	 *
	 * @return The cells whose edits changed, for the caller to redraw.
	 */
	static Set<Center> applyTerrainMove(MapEdits edits, Collection<Center> selectedCenters, Map<Center, Center> destinationToSource)
	{
		Set<Center> changedCenters = new HashSet<>();

		// Capture every source's state before anything is cleared, so a cell that is both a source and a destination is stamped from the state it
		// had before the move started.
		Map<Integer, CenterEdit> sourceStates = new HashMap<>();
		for (Center source : selectedCenters)
		{
			CenterEdit edit = edits.centerEdits.get(source.index);
			if (edit != null)
			{
				sourceStates.put(source.index, edit);
			}
		}

		Set<Center> destinationCenters = destinationToSource.keySet();
		for (Center source : selectedCenters)
		{
			if (!sourceStates.containsKey(source.index) || destinationCenters.contains(source))
			{
				// A cell that is also a destination is about to receive the terrain arriving on it, so it is not emptied.
				continue;
			}
			edits.centerEdits.put(source.index, clearedTerrainEdit(source.index));
			changedCenters.add(source);
		}

		for (Map.Entry<Center, Center> entry : destinationToSource.entrySet())
		{
			CenterEdit state = sourceStates.get(entry.getValue().index);
			if (state == null)
			{
				continue;
			}
			Center destination = entry.getKey();
			Integer regionId = state.isWater ? null : state.regionId;
			edits.centerEdits.put(destination.index, new CenterEdit(destination.index, state.isWater, state.isLake, regionId, state.icon, state.trees));
			changedCenters.add(destination);
		}

		return changedCenters;
	}

	/**
	 * The edit for a cell emptied back to open ocean: no region, not a lake, and nothing standing on it, since whatever was there belonged to
	 * the terrain that was moved away or deleted.
	 */
	static CenterEdit clearedTerrainEdit(int centerIndex)
	{
		return new CenterEdit(centerIndex, true, false, null, null, null);
	}

	private List<FreeIcon> moveIcons(Point delta)
	{
		List<FreeIcon> changedIcons = new ArrayList<>();
		if (selectedIcons.isEmpty() || updater.mapParts == null || updater.mapParts.graph == null)
		{
			return changedIcons;
		}

		double resolutionScale = getResolutionScale();
		List<FreeIcon> movedIcons = new ArrayList<>();
		for (FreeIcon icon : selectedIcons)
		{
			Point scaledLocation = icon.getScaledLocation(resolutionScale);
			Point movedLocationResolutionInvariant = new Point((scaledLocation.x + delta.x) / resolutionScale, (scaledLocation.y + delta.y) / resolutionScale);
			// Re-anchor to the cell the icon now sits on so tree/icon bookkeeping keeps pointing at the ground under it.
			Center anchor = locateCell(graphPointForResolutionInvariantPoint(movedLocationResolutionInvariant));
			FreeIcon movedIcon = icon.copyWithLocationResolutionInvariantAndCenterIndex(movedLocationResolutionInvariant, anchor == null ? null : anchor.index);
			mainWindow.edits.freeIcons.doWithLock(() ->
			{
				mainWindow.edits.freeIcons.remove(icon);
				mainWindow.edits.freeIcons.addOrReplace(movedIcon);
			});
			changedIcons.add(icon);
			changedIcons.add(movedIcon);
			movedIcons.add(movedIcon);
		}

		selectedIcons.clear();
		selectedIcons.addAll(movedIcons);
		return changedIcons;
	}

	private List<MapText> moveTexts(Point delta)
	{
		List<MapText> changedTexts = new ArrayList<>();
		if (selectedTexts.isEmpty())
		{
			return changedTexts;
		}

		double resolutionScale = getResolutionScale();
		for (MapText text : selectedTexts)
		{
			MapText positionBeforeMove = textPositionsBeforeMove.get(text);
			if (positionBeforeMove != null)
			{
				changedTexts.add(positionBeforeMove);
			}
			text.location = new Point(text.location.x + delta.x / resolutionScale, text.location.y + delta.y / resolutionScale);
			changedTexts.add(text);
		}
		return changedTexts;
	}

	/**
	 * Translates the selected roads and rivers, replacing each in the edit lists. Because both store only control-point locations, the whole
	 * path moves rigidly, which also leaves a road's dash pattern in the same phase along its length.
	 */
	private void moveRoadsAndRivers(Point deltaGraph)
	{
		if (selectedRoads.isEmpty() && selectedRivers.isEmpty())
		{
			return;
		}
		Point delta = new Point(deltaGraph.x / getResolutionScale(), deltaGraph.y / getResolutionScale());

		List<Road> movedRoads = new ArrayList<>();
		for (int i = 0; i < mainWindow.edits.roads.size(); i++)
		{
			Road road = mainWindow.edits.roads.get(i);
			if (selectedRoads.contains(road))
			{
				Road movedRoad = offsetRoad(road, delta);
				mainWindow.edits.roads.set(i, movedRoad);
				movedRoads.add(movedRoad);
			}
		}
		selectedRoads.clear();
		selectedRoads.addAll(movedRoads);

		List<River> movedRivers = new ArrayList<>();
		for (int i = 0; i < mainWindow.edits.rivers.size(); i++)
		{
			River river = mainWindow.edits.rivers.get(i);
			if (selectedRivers.contains(river))
			{
				River movedRiver = offsetRiver(river, delta);
				mainWindow.edits.rivers.set(i, movedRiver);
				movedRivers.add(movedRiver);
			}
		}
		selectedRivers.clear();
		selectedRivers.addAll(movedRivers);
	}

	/**
	 * A copy of {@code road} translated by a resolution-invariant offset. Roads carry only control-point locations, so a translated copy is
	 * the whole line moved.
	 */
	static Road offsetRoad(Road road, Point deltaResolutionInvariant)
	{
		List<RoadPathNode> nodes = new ArrayList<>(road.nodes.size());
		for (RoadPathNode node : road.nodes)
		{
			nodes.add(new RoadPathNode(node.getLoc().add(deltaResolutionInvariant)));
		}
		return new Road(nodes);
	}

	/**
	 * A copy of {@code river} translated by a resolution-invariant offset.
	 *
	 * <p>
	 * The copy drops the Voronoi edge and corner references the original carried. Those name geometry at the river's old position, and every
	 * draw snaps a node that carries one back onto it - so a moved river that kept them would be dragged straight back to where it started.
	 * Dropping them leaves the moved river a control-point path, which is what it now is, while each segment keeps its width level and
	 * jaggedness seed, so it is drawn with the same shape and style as before.
	 */
	static River offsetRiver(River river, Point deltaResolutionInvariant)
	{
		List<RiverPathNode> nodes = new ArrayList<>(river.nodes.size());
		for (RiverPathNode node : river.nodes)
		{
			nodes.add(new RiverPathNode(node.getLoc().add(deltaResolutionInvariant), node.getWidthLevelToNext(), node.getSeedToNext(), RiverPathNode.EDGE_INDEX_NONE,
					RiverPathNode.CORNER_INDEX_NONE));
		}
		return new River(nodes);
	}

	private List<Point> selectedLineNodeLocations()
	{
		List<Point> locations = new ArrayList<>();
		for (Road road : selectedRoads)
		{
			locations.addAll(PathOperations.toLocationList(road.nodes));
		}
		for (River river : selectedRivers)
		{
			locations.addAll(PathOperations.toLocationList(river.nodes));
		}
		return locations;
	}

	/**
	 * Adds the cells under the given locations to a redraw set. An incremental draw only repaints the bounds of the cells it is given, so a
	 * moved line needs the cells under its old and new positions, to have its pixels cleared from one place and drawn in the other.
	 */
	private void addCentersUnderLocations(Set<Center> centersOut, List<Point> locationsResolutionInvariant)
	{
		double resolutionScale = getResolutionScale();
		for (Point location : locationsResolutionInvariant)
		{
			Center center = locateCell(location.mult(resolutionScale));
			if (center != null)
			{
				centersOut.add(center);
			}
		}
	}

	/**
	 * Resolves a graph-space point to the cell containing it, or null when the point is off the map.
	 */
	private Center locateCell(Point graphPoint)
	{
		if (updater.mapParts == null || updater.mapParts.graph == null)
		{
			return null;
		}
		return updater.mapParts.graph.findClosestCenter(graphPoint, true);
	}

	// ---------------------------------------------------------------------------------------------------
	// Clipboard
	// ---------------------------------------------------------------------------------------------------

	private void copySelection()
	{
		if (isSelectionEmpty())
		{
			return;
		}

		copiedCells.clear();
		copiedIcons.clear();
		copiedTexts.clear();
		copiedRoads.clear();
		copiedRivers.clear();

		for (Center center : selectedCenters)
		{
			CenterEdit edit = mainWindow.edits.centerEdits.get(center.index);
			if (edit == null)
			{
				continue;
			}
			copiedCells.add(new CopiedCell(center.loc.x, center.loc.y, edit.isWater, edit.isLake, edit.regionId, edit.icon, edit.trees));
		}
		copiedIcons.addAll(selectedIcons);
		for (MapText text : selectedTexts)
		{
			copiedTexts.add(text.deepCopy());
		}
		for (Road road : selectedRoads)
		{
			copiedRoads.add(new Road(road));
		}
		for (River river : selectedRivers)
		{
			copiedRivers.add(new River(river));
		}

		Rectangle bounds = getSelectionBoundsGraph();
		clipboardOrigin = bounds == null ? null : bounds.getCenter();
		updateEditButtonStates();
	}

	private void cutSelection()
	{
		if (isSelectionEmpty())
		{
			return;
		}
		copySelection();
		deleteSelection();
	}

	private void pasteClipboard()
	{
		if (copiedCells.isEmpty() && copiedIcons.isEmpty() && copiedTexts.isEmpty() && copiedRoads.isEmpty() && copiedRivers.isEmpty())
		{
			return;
		}

		Point pasteOrigin = graphPointAtMouse();
		if (pasteOrigin == null || clipboardOrigin == null)
		{
			// No mouse position (or nothing to anchor to): drop the paste a little off the original so it does not land exactly on top of it.
			Point anchor = clipboardOrigin == null ? new Point(0, 0) : clipboardOrigin;
			double offset = 50;
			pasteOrigin = new Point(anchor.x + offset, anchor.y + offset);
		}
		Point delta = clipboardOrigin == null ? new Point(0, 0) : new Point(pasteOrigin.x - clipboardOrigin.x, pasteOrigin.y - clipboardOrigin.y);

		Set<Center> changedCenters = pasteTerrain(delta);
		List<FreeIcon> pastedIcons = pasteIcons(delta);
		List<MapText> pastedTexts = pasteTexts(delta);
		List<Road> pastedRoads = new ArrayList<>();
		List<River> pastedRivers = new ArrayList<>();
		pasteRoadsAndRivers(delta, changedCenters, pastedRoads, pastedRivers);

		undoer.setUndoPoint(UpdateType.Incremental, this);
		if (!changedCenters.isEmpty())
		{
			updater.createAndShowMapIncrementalUsingCenters(changedCenters);
		}
		if (!pastedIcons.isEmpty())
		{
			updater.createAndShowMapIncrementalUsingIcons(pastedIcons);
		}
		if (!pastedTexts.isEmpty())
		{
			updater.createAndShowMapIncrementalUsingText(pastedTexts);
		}

		selectPastedObjects(changedCenters, pastedIcons, pastedTexts, pastedRoads, pastedRivers);
	}

	/**
	 * Appends translated copies of the copied roads and rivers, and queues the cells under their new positions for redrawing.
	 */
	private void pasteRoadsAndRivers(Point deltaGraph, Set<Center> changedCentersOut, List<Road> pastedRoadsOut, List<River> pastedRiversOut)
	{
		if (copiedRoads.isEmpty() && copiedRivers.isEmpty())
		{
			return;
		}
		Point delta = new Point(deltaGraph.x / getResolutionScale(), deltaGraph.y / getResolutionScale());

		for (Road copiedRoad : copiedRoads)
		{
			Road pastedRoad = offsetRoad(copiedRoad, delta);
			mainWindow.edits.roads.add(pastedRoad);
			pastedRoadsOut.add(pastedRoad);
			addCentersUnderLocations(changedCentersOut, PathOperations.toLocationList(pastedRoad.nodes));
		}
		for (River copiedRiver : copiedRivers)
		{
			River pastedRiver = offsetRiver(copiedRiver, delta);
			mainWindow.edits.rivers.add(pastedRiver);
			pastedRiversOut.add(pastedRiver);
			addCentersUnderLocations(changedCentersOut, PathOperations.toLocationList(pastedRiver.nodes));
		}
	}

	private Set<Center> pasteTerrain(Point delta)
	{
		if (updater.mapParts == null || updater.mapParts.graph == null)
		{
			return new HashSet<>();
		}
		return stampTerrain(mainWindow.edits, copiedCells, delta, this::locateCell);
	}

	/**
	 * Stamps copied cell terrain onto the cells under each copied position plus {@code delta}, replacing whatever terrain was there. Unlike a
	 * move, this does not clear the cells the copy came from, so the same clipboard can be stamped repeatedly.
	 *
	 * @return The cells whose edits changed, for the caller to redraw.
	 */
	static Set<Center> stampTerrain(MapEdits edits, Collection<CopiedCell> copiedCells, Point delta, java.util.function.Function<Point, Center> cellLocator)
	{
		Set<Center> changedCenters = new HashSet<>();
		for (CopiedCell cell : copiedCells)
		{
			Center destination = cellLocator.apply(new Point(cell.graphX() + delta.x, cell.graphY() + delta.y));
			if (destination == null)
			{
				continue;
			}
			Integer regionId = cell.isWater() ? null : cell.regionId();
			edits.centerEdits.put(destination.index, new CenterEdit(destination.index, cell.isWater(), cell.isLake(), regionId, cell.icon(), cell.trees()));
			changedCenters.add(destination);
		}
		return changedCenters;
	}

	private List<FreeIcon> pasteIcons(Point delta)
	{
		List<FreeIcon> pastedIcons = new ArrayList<>();
		if (copiedIcons.isEmpty() || updater.mapParts == null || updater.mapParts.graph == null)
		{
			return pastedIcons;
		}

		double resolutionScale = getResolutionScale();
		for (FreeIcon icon : copiedIcons)
		{
			Point scaledLocation = icon.getScaledLocation(resolutionScale);
			Point pastedLocationResolutionInvariant = new Point((scaledLocation.x + delta.x) / resolutionScale, (scaledLocation.y + delta.y) / resolutionScale);
			Center anchor = locateCell(graphPointForResolutionInvariantPoint(pastedLocationResolutionInvariant));
			FreeIcon pastedIcon = icon.copyWithLocationResolutionInvariantAndCenterIndex(pastedLocationResolutionInvariant, anchor == null ? null : anchor.index);
			mainWindow.edits.freeIcons.doWithLock(() -> mainWindow.edits.freeIcons.addOrReplace(pastedIcon));
			pastedIcons.add(pastedIcon);
		}
		return pastedIcons;
	}

	private List<MapText> pasteTexts(Point delta)
	{
		List<MapText> pastedTexts = new ArrayList<>();
		double resolutionScale = getResolutionScale();
		for (MapText copiedText : copiedTexts)
		{
			MapText pastedText = copiedText.deepCopy();
			pastedText.location = new Point(copiedText.location.x + delta.x / resolutionScale, copiedText.location.y + delta.y / resolutionScale);
			pastedText.line1Bounds = null;
			pastedText.line2Bounds = null;
			mainWindow.edits.text.add(pastedText);
			pastedTexts.add(pastedText);
		}
		return pastedTexts;
	}

	private void selectPastedObjects(Set<Center> pastedCenters, List<FreeIcon> pastedIcons, List<MapText> pastedTexts, List<Road> pastedRoads, List<River> pastedRivers)
	{
		selectedCenters.clear();
		selectedIcons.clear();
		selectedTexts.clear();
		selectedRoads.clear();
		selectedRivers.clear();
		selectedCenters.addAll(pastedCenters);
		selectedIcons.addAll(pastedIcons);
		selectedTexts.addAll(pastedTexts);
		selectedRoads.addAll(pastedRoads);
		selectedRivers.addAll(pastedRivers);
		updateSelectionOverlay();
	}

	// ---------------------------------------------------------------------------------------------------
	// Delete
	// ---------------------------------------------------------------------------------------------------

	private void deleteSelection()
	{
		if (isSelectionEmpty())
		{
			return;
		}

		Set<Center> changedCenters = new HashSet<>();
		for (Center center : selectedCenters)
		{
			CenterEdit edit = mainWindow.edits.centerEdits.get(center.index);
			if (edit == null)
			{
				continue;
			}
			mainWindow.edits.centerEdits.put(center.index, clearedTerrainEdit(center.index));
			changedCenters.add(center);
		}

		List<FreeIcon> deletedIcons = new ArrayList<>(selectedIcons);
		if (!deletedIcons.isEmpty())
		{
			mainWindow.edits.freeIcons.doWithLock(() -> mainWindow.edits.freeIcons.removeAll(deletedIcons));
		}

		addCentersUnderLocations(changedCenters, selectedLineNodeLocations());
		mainWindow.edits.roads.removeAll(selectedRoads);
		mainWindow.edits.rivers.removeAll(selectedRivers);

		List<MapText> changedTexts = new ArrayList<>();
		for (MapText text : selectedTexts)
		{
			changedTexts.add(text.deepCopy());
			text.value = "";
		}

		undoer.setUndoPoint(UpdateType.Incremental, this);
		if (!changedCenters.isEmpty())
		{
			updater.createAndShowMapIncrementalUsingCenters(changedCenters);
		}
		if (!deletedIcons.isEmpty())
		{
			updater.createAndShowMapIncrementalUsingIcons(deletedIcons);
		}
		if (!changedTexts.isEmpty())
		{
			purgeEmptyTextWhenIdle();
			updater.createAndShowMapIncrementalUsingText(changedTexts);
		}

		clearSelection();
	}

	/**
	 * Reaps the text entries whose value was just cleared, once no draw is in flight. Waiting matters because the incremental text draw needs
	 * those entries to still be in the list to compute the bounds of the pixels to erase.
	 */
	private void purgeEmptyTextWhenIdle()
	{
		updater.doWhenMapIsNotDrawing(() ->
		{
			if (mainWindow.edits != null && mainWindow.edits.isInitialized())
			{
				mainWindow.edits.purgeEmptyText();
			}
		});
	}

	private Point graphPointAtMouse()
	{
		java.awt.Point mouseLocation = mapEditingPanel.getMousePosition();
		return mouseLocation == null ? null : getPointOnGraph(mouseLocation);
	}
}
