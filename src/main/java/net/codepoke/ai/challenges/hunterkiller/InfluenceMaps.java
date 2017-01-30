package net.codepoke.ai.challenges.hunterkiller;

import static net.codepoke.lib.util.ai.search.graph.GraphSearch.breadthFirst;

import java.util.ArrayList;
import java.util.List;

import lombok.AllArgsConstructor;
import lombok.Data;
import net.codepoke.ai.challenge.hunterkiller.HunterKillerState;
import net.codepoke.ai.challenge.hunterkiller.Map;
import net.codepoke.ai.challenge.hunterkiller.gameobjects.GameObject;
import net.codepoke.ai.challenge.hunterkiller.gameobjects.mapfeature.Door;
import net.codepoke.ai.challenge.hunterkiller.gameobjects.mapfeature.MapFeature;
import net.codepoke.ai.challenge.hunterkiller.gameobjects.mapfeature.Structure;
import net.codepoke.lib.util.ai.SearchContext;
import net.codepoke.lib.util.datastructures.MatrixMap;
import net.codepoke.lib.util.datastructures.MatrixMap.MatrixExpansionStrategy;
import net.codepoke.lib.util.datastructures.Path;
import one.util.streamex.StreamEx;

/**
 * This class contains methods that create an influence map for a variety of subjects, including:
 * 
 * <ul>
 * <li>Distance to structures</li>
 * </ul>
 * 
 * @author Anton Valkenberg (anton.valkenberg@gmail.com)
 *
 */
public class InfluenceMaps {

	/**
	 * Helper class for representing a position with a value.
	 * 
	 * @author Anton Valkenberg (anton.valkenberg@gmail.com)
	 *
	 */
	@Data
	@AllArgsConstructor
	private static class Point {

		/**
		 * The location of this point in a {@link MatrixMap}.
		 */
		int location;
		/**
		 * The numeric value of this point.
		 */
		int value;

	}

	/**
	 * Creates a {@link MatixMap} containing the distances to the closest structure for all locations on the {@link Map}
	 * .
	 * 
	 * @param state
	 *            The game state to create the map for.
	 * @param structures
	 *            Collection of {@link Structure}s to include when determining what a location's distance to any of them
	 *            is.
	 */
	@SuppressWarnings("unchecked")
	public static MatrixMap createMap_DistanceToStructures(HunterKillerState state, List<Structure> structures) {
		// Get the objects from the state that we need to query
		Map map = state.getMap();

		// Create the MatrixMap to mimic the game map
		MatrixMap valueMap = new MatrixMap(map.getMapWidth(), map.getMapHeight());
		valueMap.reset(-1);

		// Domain, Position, Action, Subject, ...
		SearchContext<MatrixMap, Point, Point, GameObject, Path<Point>> context = new SearchContext<>();

		// Our domain will be the MatrixMap we are filling with values
		context.domain(valueMap);
		// We will do a breadth-first search and use an empty solution-strategy
		context.search(breadthFirst((c, current) -> {
			// We don't actually need to return a path to the target, since we only care about the distance
			return null;
		}));
		// The goal strategy will be to always return false, which makes sure we visit every node we can reach
		context.goal((c, current) -> false);
		// The expansion strategy determines which nodes among the children should still be visited
		context.expansion((c, current) -> {
			// Get all children through expansion, we use Orthogonal expansion here because we can only move
			// orthogonally in HunterKiller
			Iterable<Integer> children = MatrixExpansionStrategy.EXPANSION_ORTHOGONAL.expand(c, current.location);

			// Add +1 to value
			int nextValue = current.value + 1;

			// Filter on positions we can expand from
			StreamEx<Integer> expandables = StreamEx.of(children.iterator())
													// Filter out any features that block line-of-sight, except for
													// Doors
													.filter(i -> {
														MapFeature feature = map.getFeatureAtLocation(map.toLocation(i));
														return !feature.isBlockingLOS() || feature instanceof Door;
													})
													// Filter out any positions that have already been calculated and
													// have a lower value than the 'nextValue'
													.filter(i -> {
														int val = c.domain()
																	.get(i);
														return val == -1 || val > nextValue;
													});

			// Update the values of the children that turned out to still be expandable
			List<Point> returnList = new ArrayList<Point>();
			for (Integer number : expandables) {
				// Set the current value (i.e. the distance we have currently travelled)
				c.domain()
					.set(number, nextValue);
				// Add this node to the list of nodes to expand further
				returnList.add(new Point(number, nextValue));
			}

			// Return the list that can still be expanded
			return returnList;
		});

		// Now that we have defined our search context, execute the actual searches
		for (Structure structure : structures) {
			// Set the structure as the source for the search
			context.source(new Point(map.toPosition(structure.getLocation()), -1));
			// Execute it
			context.execute();
		}

		// System.out.println(valueMap);
		return valueMap;
	}

	/**
	 * Returns a two dimensional array (1st: width, 2nd: height) containing the normalised values of the
	 * {@link MatrixMap}.
	 * 
	 * @param map
	 *            The map to convert.
	 */
	public static float[][] convertToValues(MatrixMap map) {
		// Create the array to return
		float[][] values = new float[map.getWidth()][map.getHeight()];

		// Get the range of values contained in the map
		int[] valueRange = map.findRange();

		// Go through the array and check each location
		for (int x = 0; x < values.length; x++) {
			for (int y = 0; y < values[x].length; y++) {
				// Get the position in the MatrixMap for this location
				int mapPosition = map.convert(x, y);
				// Set the normalised value
				values[x][y] = map.getNormalized(mapPosition, valueRange);
			}
		}

		return values;
	}

}
