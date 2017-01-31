package net.codepoke.ai.challenges.hunterkiller;

import static net.codepoke.ai.challenges.hunterkiller.StreamExtensions.stream;
import static net.codepoke.lib.util.ai.search.graph.GraphSearch.breadthFirst;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Set;
import java.util.function.Function;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
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
	 * Represents a collection of {@link KnowledgeLayer}s. The layers are stored in a {@link HashMap}, indexed by their
	 * name.
	 * 
	 * @author Anton Valkenberg (anton.valkenberg@gmail.com)
	 *
	 */
	public static class KnowledgeBase {

		/**
		 * Collection of the knowledge layers, indexed by name.
		 */
		HashMap<String, KnowledgeLayer> layers = new HashMap<>();

		/**
		 * Whether or not this KnowledgeBase contains a layer with the provided name.
		 * 
		 * @param key
		 *            The name of the layer.
		 */
		public boolean containsKey(String key) {
			return layers.containsKey(key);
		}

		/**
		 * Adds a function to the KnowledgeBase with the provided key as it's name. If the KnowledgeBase previously
		 * contained a mapping for the key, the old value is returned.
		 * 
		 * @param key
		 *            The name for the KnowledgeLayer.
		 * @param function
		 *            The function that the KnowledgeLayer will invoke to update it's values.
		 * @return The previous value associated with the key, or null if there was no mapping.
		 */
		public KnowledgeLayer put(String key, Function<HunterKillerState, MatrixMap> function) {
			return layers.put(key, new KnowledgeLayer(key, function));
		}

		/**
		 * Adds a KnowledgeLayer to the KnowledgeBase. If the KnowledgeBase previously contained a mapping for it's key,
		 * the old value is returned.
		 * 
		 * @param layer
		 *            The KnowledgeLayer.
		 * @return The previous value associated with the layer's key, or null if there was no mapping.
		 */
		public KnowledgeLayer put(KnowledgeLayer layer) {
			return layers.put(layer.name, layer);
		}

		/**
		 * Removes the mapping of the specified key to a KnowledgeLayer from the KnowledgeBase if present.
		 * 
		 * @param key
		 *            The name of the KnowledgeLayer that should be removed from the KnowledgeBase.
		 * @return The previous KnowledgeLayer associated with the key, or null if there was no mapping.
		 */
		public KnowledgeLayer remove(String key) {
			return layers.remove(key);
		}

		/**
		 * Returns the KnowledgeLayer that is mapped to the specified key, or null if the KnowledgeBase contains no
		 * mapping for this key.
		 * 
		 * @param key
		 *            The name of the KnowledgeLayer that should be returned.
		 */
		public KnowledgeLayer get(String key) {
			return layers.get(key);
		}

		/**
		 * Returns a {@link Collection} of KnowledgeLayers that are currently contained in this KnowledgeBase.
		 */
		public Collection<KnowledgeLayer> values() {
			return layers.values();
		}

		/**
		 * Returns a {@link Set} of names that currently have a mapping in this KnowledgeBase.
		 */
		public Set<String> keySet() {
			return layers.keySet();
		}

		/**
		 * Updates all KnowledgeLayers in this KnowledgeBase with the provided state.
		 * 
		 * @param state
		 *            The state that should be provided to the layers to update them.
		 */
		public void update(HunterKillerState state) {
			for (KnowledgeLayer layer : layers.values()) {
				layer.update(state);
			}
		}

	}

	/**
	 * Represents a map of values, that mimics the {@link Map} of the game, containing some knowledge about the domain.
	 * The name of a layer should describe the kind of knowledge.
	 * 
	 * @author Anton Valkenberg (anton.valkenberg@gmail.com)
	 *
	 */
	@RequiredArgsConstructor
	public static class KnowledgeLayer {

		/**
		 * The map of values that make up this layer.
		 */
		@Getter
		MatrixMap map;

		/**
		 * The name of this layer, describing the knowledge contained in it.
		 */
		@Getter
		final String name;

		/**
		 * The {@link Function} that is to be invoked to set and update the values in the map. This function has a
		 * {@link HunterKillerState} as argument and returns a {@link MatrixMap} of values.
		 */
		final Function<HunterKillerState, MatrixMap> function;

		/**
		 * Updates this layer using the provided state.
		 * 
		 * @param state
		 *            The state to use as argument when invoking this layer's function.
		 */
		public void update(HunterKillerState state) {
			map = function.apply(state);
		}

	}

	/**
	 * Returns a {@link MatrixMap} containing the distance to the closest enemy structure for each location on the map.
	 * 
	 * @param state
	 *            The state of the game.
	 */
	public static MatrixMap calculateDistanceToEnemyStructures(HunterKillerState state) {
		// Get a list of all structures that are not controlled by the currently active player
		List<Structure> enemyStructures = stream(state.getMap(), Structure.class).filter(i -> !i.isControlledBy(state.getActivePlayer()))
																					.toList();

		// Calculate the distance map
		return InfluenceMaps.createMap_DistanceToStructures(state, enemyStructures);
	}

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
