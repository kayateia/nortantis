package nortantis.swing;

import nortantis.editor.CenterIcon;
import nortantis.editor.CenterIconType;
import nortantis.editor.CenterEdit;
import nortantis.editor.CenterTrees;
import nortantis.editor.FreeIcon;
import nortantis.editor.River;
import nortantis.editor.RiverPathNode;
import nortantis.editor.Road;
import nortantis.IconType;
import nortantis.geom.Point;
import nortantis.graph.voronoi.Center;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the cell remapping the lasso tool does when terrain is moved, pasted, or deleted. These run against a synthetic grid of cells and a
 * locator that snaps a point to the nearest one, standing in for the world graph's own center lookup, so the remapping rules can be checked
 * without generating a map.
 */
public class LassoSelectionToolTest
{
	private static final int gridSpacing = 10;
	private static final int gridExtent = 20;

	private static List<Center> createGrid()
	{
		List<Center> centers = new ArrayList<>();
		int index = 0;
		for (int y = 0; y <= gridExtent; y += gridSpacing)
		{
			for (int x = 0; x <= gridExtent; x += gridSpacing)
			{
				Center center = new Center(new Point(x, y));
				center.index = index++;
				centers.add(center);
			}
		}
		return centers;
	}

	/** Stands in for {@link nortantis.WorldGraph#findClosestCenter}, including returning null off the edge of the map. */
	private static Center locate(List<Center> centers, Point point)
	{
		long x = Math.round(point.x / gridSpacing) * (long) gridSpacing;
		long y = Math.round(point.y / gridSpacing) * (long) gridSpacing;
		if (x < 0 || y < 0 || x > gridExtent || y > gridExtent)
		{
			return null;
		}
		for (Center center : centers)
		{
			if (center.loc.x == x && center.loc.y == y)
			{
				return center;
			}
		}
		return null;
	}

	private static Center centerAt(List<Center> centers, int x, int y)
	{
		return locate(centers, new Point(x, y));
	}

	private static MapEdits createEdits(List<Center> centers)
	{
		MapEdits edits = new MapEdits();
		for (Center center : centers)
		{
			edits.centerEdits.put(center.index, new CenterEdit(center.index, false, false, null, null, null));
		}
		return edits;
	}

	private static void setCenterEdit(MapEdits edits, Center center, boolean isWater, boolean isLake, Integer regionId, CenterIcon icon, CenterTrees trees)
	{
		edits.centerEdits.put(center.index, new CenterEdit(center.index, isWater, isLake, regionId, icon, trees));
	}

	/** Runs the same two steps the tool does when a move is committed. */
	private static Set<Center> moveCells(MapEdits edits, List<Center> centers, Collection<Center> selected, Point delta)
	{
		Map<Center, Center> destinationToSource = LassoSelectionTool.mapMovedCells(selected, centers, delta, gridSpacing, point -> locate(centers, point));
		return LassoSelectionTool.applyTerrainMove(edits, selected, destinationToSource);
	}

	private static Center newCenter(int index, double x, double y)
	{
		Center center = new Center(new Point(x, y));
		center.index = index;
		return center;
	}

	/** Nearest-cell lookup over an arbitrary set of cells, standing in for the graph's own lookup. */
	private static Center nearest(List<Center> centers, Point point)
	{
		Center best = null;
		double bestDistance = Double.POSITIVE_INFINITY;
		for (Center center : centers)
		{
			double distance = center.loc.distanceTo(point);
			if (distance < bestDistance)
			{
				bestDistance = distance;
				best = center;
			}
		}
		return bestDistance > gridExtent * 2 ? null : best;
	}

	@Test
	public void movingLandMovesItsStateAndLeavesOceanBehind()
	{
		List<Center> centers = createGrid();
		MapEdits edits = createEdits(centers);
		Center source = centerAt(centers, 0, 0);
		Center destination = centerAt(centers, 0, 10);
		setCenterEdit(edits, source, false, false, 7, null, null);

		Set<Center> changed = moveCells(edits, centers, List.of(source), new Point(0, gridSpacing));

		CenterEdit sourceAfter = edits.centerEdits.get(source.index);
		assertTrue(sourceAfter.isWater, "The cell the land left should become water.");
		assertFalse(sourceAfter.isLake, "Vacated cells become ocean, not lakes.");
		assertNull(sourceAfter.regionId, "Ocean carries no region.");

		CenterEdit destinationAfter = edits.centerEdits.get(destination.index);
		assertFalse(destinationAfter.isWater, "The destination should become the land that moved onto it.");
		assertEquals(Integer.valueOf(7), destinationAfter.regionId, "The region should travel with the land.");

		assertTrue(changed.contains(source));
		assertTrue(changed.contains(destination));
	}

	@Test
	public void movingByNothingLeavesTerrainUnchanged()
	{
		List<Center> centers = createGrid();
		MapEdits edits = createEdits(centers);
		Center center = centerAt(centers, 10, 10);
		setCenterEdit(edits, center, false, true, 3, null, null);

		moveCells(edits, centers, List.of(center), new Point(0, 0));

		// The cell is emptied and then refilled from its own captured state, so it must survive its own move.
		CenterEdit after = edits.centerEdits.get(center.index);
		assertFalse(after.isWater);
		assertTrue(after.isLake);
		assertEquals(Integer.valueOf(3), after.regionId);
	}

	@Test
	public void movingOffTheMapOnlyClearsTheSource()
	{
		List<Center> centers = createGrid();
		MapEdits edits = createEdits(centers);
		Center source = centerAt(centers, 20, 20);
		setCenterEdit(edits, source, false, false, 4, null, null);

		Set<Center> changed = moveCells(edits, centers, List.of(source), new Point(gridSpacing, gridSpacing));

		assertEquals(1, changed.size());
		assertTrue(edits.centerEdits.get(source.index).isWater);
	}

	@Test
	public void movingCarriesIconsAndTreesStoredAsCenterEdits()
	{
		List<Center> centers = createGrid();
		MapEdits edits = createEdits(centers);
		Center source = centerAt(centers, 0, 0);
		Center destination = centerAt(centers, 10, 0);
		CenterIcon icon = new CenterIcon(CenterIconType.Mountain, "artpack", "range", 4);
		CenterTrees trees = new CenterTrees("artpack", "oak", 0.5, 42L);
		setCenterEdit(edits, source, false, false, null, icon, trees);

		moveCells(edits, centers, List.of(source), new Point(gridSpacing, 0));

		CenterEdit destinationAfter = edits.centerEdits.get(destination.index);
		assertSame(icon, destinationAfter.icon);
		assertSame(trees, destinationAfter.trees);
		assertNull(edits.centerEdits.get(source.index).icon);
		assertNull(edits.centerEdits.get(source.index).trees);
	}

	@Test
	public void movedLandStaysSolidWhenProjectingCellsForwardWouldCollide()
	{
		// Cells unevenly spaced along one row, spaced so that projecting both selected cells forward by this offset lands them on the same
		// cell and leaves the other behind. That lost cell is what showed up as a patch of ocean punched into the middle of a moved landmass;
		// resolving each destination from the selection's side instead covers both.
		List<Center> centers = new ArrayList<>();
		centers.add(newCenter(0, 0, 0));
		centers.add(newCenter(1, 5, 0));
		centers.add(newCenter(2, 20, 0));
		MapEdits edits = createEdits(centers);
		setCenterEdit(edits, centers.get(2), true, false, null, null, null);
		List<Center> selected = List.of(centers.get(0), centers.get(1));
		Point delta = new Point(3, 0);

		Map<Center, Center> destinationToSource = LassoSelectionTool.mapMovedCells(selected, centers, delta, gridSpacing, point -> nearest(centers, point));
		assertEquals(2, destinationToSource.size(), "Both cells of the moved block must land somewhere.");

		LassoSelectionTool.applyTerrainMove(edits, selected, destinationToSource);

		long landCells = centers.stream().filter(center -> !edits.centerEdits.get(center.index).isWater).count();
		assertEquals(2, landCells, "The moved land must stay two cells wide, with no ocean punched into it.");
	}

	@Test
	public void pastingStampsTerrainWithoutClearingTheSource()
	{
		List<Center> centers = createGrid();
		MapEdits edits = createEdits(centers);
		Center source = centerAt(centers, 0, 0);
		Center destination = centerAt(centers, 10, 10);
		setCenterEdit(edits, source, false, true, 2, null, null);

		List<LassoSelectionTool.CopiedCell> copied = List.of(new LassoSelectionTool.CopiedCell(source.loc.x, source.loc.y, false, true, 2, null, null));
		Set<Center> changed = LassoSelectionTool.stampTerrain(edits, copied, new Point(gridSpacing, gridSpacing), point -> locate(centers, point));

		CenterEdit sourceAfter = edits.centerEdits.get(source.index);
		assertFalse(sourceAfter.isWater, "Pasting leaves the copied cells alone.");
		assertTrue(sourceAfter.isLake);

		CenterEdit destinationAfter = edits.centerEdits.get(destination.index);
		assertFalse(destinationAfter.isWater);
		assertTrue(destinationAfter.isLake);
		assertEquals(Integer.valueOf(2), destinationAfter.regionId);
		assertTrue(changed.contains(destination));
	}

	@Test
	public void clearedTerrainEditIsOpenOcean()
	{
		CenterEdit edit = LassoSelectionTool.clearedTerrainEdit(11);

		assertEquals(11, edit.index);
		assertTrue(edit.isWater);
		assertFalse(edit.isLake);
		assertNull(edit.regionId);
		assertNull(edit.icon);
		assertNull(edit.trees);
	}

	@Test
	public void movingIconsReanchorsThemToTheDestinationCell()
	{
		// FreeIcon anchors are what tie trees and other icons to the cell they stand on; a move has to repoint them or the next draw would
		// replant trees on the cells the land left.
		FreeIcon icon = new FreeIcon(new Point(5, 5), 1.0, IconType.trees, "artpack", "oak", 0, null, 3, 0.0, null, null, false, false, 1.0);
		FreeIcon moved = icon.copyWithLocationResolutionInvariantAndCenterIndex(new Point(15, 5), 9);

		assertEquals(Integer.valueOf(9), moved.centerIndex);
		assertEquals(15.0, moved.locationResolutionInvariant.x, 0.0001);
		assertEquals(5.0, moved.locationResolutionInvariant.y, 0.0001);
		assertEquals(Integer.valueOf(3), icon.centerIndex, "The original icon is left untouched.");
	}

	@Test
	public void movingARoadTranslatesEveryControlPoint()
	{
		Road road = Road.fromLocations(List.of(new Point(10, 20), new Point(30, 20), new Point(40, 30)));

		Road moved = LassoSelectionTool.offsetRoad(road, new Point(5, -4));

		assertEquals(3, moved.nodes.size());
		assertEquals(15.0, moved.nodes.get(0).getLoc().x, 0.0001);
		assertEquals(16.0, moved.nodes.get(0).getLoc().y, 0.0001);
		assertEquals(45.0, moved.nodes.get(2).getLoc().x, 0.0001);
		assertEquals(26.0, moved.nodes.get(2).getLoc().y, 0.0001);
		assertEquals(10.0, road.nodes.get(0).getLoc().x, 0.0001, "The original road is left untouched.");
	}

	@Test
	public void movingARiverKeepsItsSegmentShapeButDropsTheOldGraphReferences()
	{
		River river = new River(List.of(new RiverPathNode(new Point(10, 10), 2, 99L, 7, 3), new RiverPathNode(new Point(20, 10), 4, 123L, 8, RiverPathNode.CORNER_INDEX_NONE)));

		River moved = LassoSelectionTool.offsetRiver(river, new Point(-6, 2));

		assertEquals(2, moved.nodes.size());
		assertEquals(4.0, moved.nodes.get(0).getLoc().x, 0.0001);
		assertEquals(12.0, moved.nodes.get(0).getLoc().y, 0.0001);
		// Width and seed travel with the segment, so the moved river is drawn with the shape and style it had.
		assertEquals(2, moved.nodes.get(0).getWidthLevelToNext());
		assertEquals(99L, moved.nodes.get(0).getSeedToNext());
		assertEquals(4, moved.nodes.get(1).getWidthLevelToNext());
		assertEquals(123L, moved.nodes.get(1).getSeedToNext());
		// The old Voronoi edge and corner would drag the moved nodes straight back to where the river started.
		for (RiverPathNode node : moved.nodes)
		{
			assertEquals(RiverPathNode.EDGE_INDEX_NONE, node.getEdgeIndexToNext());
			assertEquals(RiverPathNode.CORNER_INDEX_NONE, node.getCornerIndexAnchor());
		}
		assertEquals(7, river.nodes.get(0).getEdgeIndexToNext(), "The original river is left untouched.");
	}
}
