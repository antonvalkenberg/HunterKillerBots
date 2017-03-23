package net.codepoke.ai.challenges.hunterkiller;

import static net.codepoke.ai.challenges.hunterkiller.StreamExtensions.stream;
import static net.codepoke.lib.util.ai.search.graph.GraphSearch.breadthFirst;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import net.codepoke.ai.challenge.hunterkiller.HunterKillerState;
import net.codepoke.ai.challenge.hunterkiller.Map;
import net.codepoke.ai.challenge.hunterkiller.MapLocation;
import net.codepoke.ai.challenge.hunterkiller.gameobjects.Controlled;
import net.codepoke.ai.challenge.hunterkiller.gameobjects.GameObject;
import net.codepoke.ai.challenge.hunterkiller.gameobjects.mapfeature.Structure;
import net.codepoke.ai.challenge.hunterkiller.gameobjects.unit.Unit;
import net.codepoke.lib.util.ai.SearchContext;
import net.codepoke.lib.util.datastructures.MatrixMap;
import net.codepoke.lib.util.datastructures.MatrixMap.MatrixExpansionStrategy;
import net.codepoke.lib.util.datastructures.Path;
import one.util.streamex.StreamEx;

import com.badlogic.gdx.graphics.Color;

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
	 * {@link InfluenceMaps#calculateDistanceToEnemyStructures(HunterKillerState)}
	 */
	public static MatrixMap calculateDistanceToAlliedStructures(HunterKillerState state) {
		// Get a list of all structures that are controlled by the currently active player
		List<Structure> allyStructures = stream(state.getMap(), Structure.class).filter(i -> i.isControlledBy(state.getActivePlayer()))
																				.toList();
		// Calculate the distance map
		return InfluenceMaps.createMap_DistanceToStructures(state, allyStructures);
	}

	/**
	 * {@link InfluenceMaps#calculateDistanceToEnemyStructures(HunterKillerState)}
	 */
	public static MatrixMap calculateDistanceToEnemyUnits(HunterKillerState state) {
		// Get a list of all units that are not controlled by the currently active player
		List<Unit> enemyUnits = stream(state.getMap(), Unit.class).filter(i -> !i.isControlledBy(state.getActivePlayer()))
																	.toList();
		// Calculate the distance map
		return InfluenceMaps.createMap_DistanceToUnits(state, enemyUnits);
	}

	/**
	 * {@link InfluenceMaps#calculateDistanceToEnemyStructures(HunterKillerState)}
	 */
	public static MatrixMap calculateDistanceToAlliedUnits(HunterKillerState state) {
		// Get a list of all units that are controlled by the currently active player
		List<Unit> allyUnits = stream(state.getMap(), Unit.class).filter(i -> i.isControlledBy(state.getActivePlayer()))
																	.toList();
		// Calculate the distance map
		return InfluenceMaps.createMap_DistanceToUnits(state, allyUnits);
	}

	/** {@link InfluenceMaps#calculateDistanceToEnemyStructures(HunterKillerState)} */
	public static MatrixMap calculateDistanceToAnyEnemy(HunterKillerState state) {
		// Get a list of all enemies' locations, either structure or unit
		List<MapLocation> enemyLocations = stream(state.getMap(), GameObject.class).filter(i -> i instanceof Controlled)
																					.filter(i -> !((Controlled) i).isControlledBy(state.getActivePlayer()))
																					.map(i -> i.getLocation())
																					.toList();

		// Calculate the distance map
		return InfluenceMaps.createMap_DistanceTo(state, enemyLocations);
	}

	/** {@link InfluenceMaps#calculateDistanceToEnemyStructures(HunterKillerState)} */
	public static MatrixMap calculateAllyPresence(HunterKillerState state) {
		// Get a list of all units that are controlled by the currently active player
		List<Unit> allyUnits = stream(state.getMap(), Unit.class).filter(i -> i.isControlledBy(state.getActivePlayer()))
																	.toList();
		// Calculate the presence map
		return InfluenceMaps.createMap_Presence(state, allyUnits);
	}

	/** {@link InfluenceMaps#calculateDistanceToEnemyStructures(HunterKillerState)} */
	public static MatrixMap calculateSquadPresence(HunterKillerState state) {
		// Find the active player's base (command center)
		Optional<Structure> base = stream(state.getMap(), Structure.class).findFirst(i -> i.isControlledBy(state.getActivePlayer())
																							&& i.isCommandCenter());
		// Get a list of all units that are controlled by the currently active player
		List<Unit> allyUnits = stream(state.getMap(), Unit.class).filter(i -> i.isControlledBy(state.getActivePlayer()))
																	.toList();
		// Calculate the squad map
		return InfluenceMaps.createMap_Squad(state, base, allyUnits);
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

		@Override
		public boolean equals(Object obj) {
			if (this == obj)
				return true;
			if (obj == null)
				return false;
			if (getClass() != obj.getClass())
				return false;
			Point other = (Point) obj;
			if (location != other.location)
				return false;
			return true;
		}

		@Override
		public int hashCode() {
			final int prime = 31;
			int result = 1;
			result = prime * result + location;
			return result;
		}

	}

	/**
	 * Creates a {@link MatixMap} containing the distance to the closest structure.
	 * {@link InfluenceMaps#createMap_DistanceTo(HunterKillerState, List)}
	 */
	public static MatrixMap createMap_DistanceToStructures(HunterKillerState state, List<Structure> structures) {
		return createMap_DistanceTo(state, StreamEx.of(structures)
													.map(i -> i.getLocation())
													.toList());
	}

	/**
	 * Creates a {@link MatixMap} containing the distance to the closest unit.
	 * {@link InfluenceMaps#createMap_DistanceTo(HunterKillerState, List)}
	 */
	public static MatrixMap createMap_DistanceToUnits(HunterKillerState state, List<Unit> units) {
		return createMap_DistanceTo(state, StreamEx.of(units)
													.map(i -> i.getLocation())
													.toList());
	}

	/**
	 * Creates a {@link MatixMap} containing the amount of presence exerted by units.
	 * {@link InfluenceMaps#createMap_DistanceTo(HunterKillerState, List)}
	 */
	@SuppressWarnings("unchecked")
	public static MatrixMap createMap_Presence(HunterKillerState state, List<Unit> units) {
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

			// Because we'll be looking 1 square/position further, reduce the value
			int nextValue = Math.max(0, current.value - 1);

			// Filter on positions we can expand from
			StreamEx<Integer> expandables = StreamEx.of(children.iterator())
													// Filter out any features that can't be traversed
													.filter(i -> map.getFeatureAtLocation(map.toLocation(i))
																	.isWalkable())
													// Filter out any positions that have a value higher than the
													// current value, or have not been visited yet
													.filter(i -> {
														int val = c.domain()
																	.get(i);
														return val == -1 || val < nextValue;
													});

			// Update the values of the children that turned out to still be expandable
			List<Point> returnList = new ArrayList<Point>();
			for (Integer number : expandables) {
				// Adjust the value by our next value
				c.domain()
					.set(number, nextValue);
				// Add this node to the list of nodes to expand further
				returnList.add(new Point(number, nextValue));
			}

			// Return the list that can still be expanded
			return returnList;
		});

		// Now that we have defined our search context, execute the actual searches
		for (Unit unit : units) {
			// Set the unit's location as the source for the search, starting value is 10
			context.source(new Point(map.toPosition(unit.getLocation()), 5));
			// Execute it
			context.execute();
		}

		// System.out.println(valueMap);
		return valueMap;
	}

	/**
	 * Creates a {@link MatixMap} containing the amount of friendly units in the neighbourhood of a location, for all
	 * locations on the {@link Map}.
	 * 
	 * @param state
	 *            The game state to create the map for.
	 * @param base
	 *            The structure to use as source for the search, normally a player's command center. Optional since it
	 *            might be destroyed during the game.
	 * @param units
	 *            The units that the player controls.
	 */
	@SuppressWarnings("unchecked")
	public static MatrixMap createMap_Squad(HunterKillerState state, Optional<Structure> base, List<Unit> units) {
		// Get the objects from the state that we need to query
		Map map = state.getMap();

		// Create the MatrixMap to mimic the game map
		MatrixMap valueMap = new MatrixMap(map.getMapWidth(), map.getMapHeight());
		valueMap.reset(-1);

		// Check if a base was found, might be destroyed. If that is the case, return now
		if (!base.isPresent())
			return valueMap;

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

			// Filter on positions we can expand from
			StreamEx<Integer> expandables = StreamEx.of(children.iterator())
													// Filter out any features that can't be traversed
													.filter(i -> map.getFeatureAtLocation(map.toLocation(i))
																	.isWalkable());

			// Update the values of the children that turned out to still be expandable
			List<Point> returnList = new ArrayList<Point>();
			for (Integer number : expandables) {

				// We define that a unit is within range of a location if its no more than 2 squares away
				int unitsInRange = 0;
				for (Unit unit : units) {
					if (MapLocation.getManhattanDist(map.toLocation(number), unit.getLocation()) <= 2) {
						unitsInRange++;
					}
				}

				// Determine value for this expandable
				int value = unitsInRange;

				// Adjust the value to the maximum value
				c.domain()
					.set(number, Math.max(c.domain()
											.get(number), value));
				// Add this node to the list of nodes to expand further
				returnList.add(new Point(number, 0));
			}

			// Return the list that can still be expanded
			return returnList;
		});

		// Now that we have defined our search context, execute the actual search
		context.source(new Point(map.toPosition(base.get()
													.getLocation()), 0));
		context.execute();

		// System.out.println(valueMap);
		return valueMap;
	}

	/**
	 * Creates a {@link MatixMap} containing the distance to the closest location among the specified locations, for all
	 * locations on the {@link Map}.
	 * 
	 * @param state
	 *            The game state to create the map for.
	 * @param locations
	 *            Collection of {@link MapLocation}s to include when determining what a location's distance to any of
	 *            them
	 *            is.
	 */
	@SuppressWarnings("unchecked")
	public static MatrixMap createMap_DistanceTo(HunterKillerState state, List<MapLocation> locations) {
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
													// Filter out any features that can't be traversed
													.filter(i -> map.getFeatureAtLocation(map.toLocation(i))
																	.isWalkable())
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
		for (MapLocation location : locations) {
			// Set the objects's location as the source for the search
			context.source(new Point(map.toPosition(location), -1));
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

	/**
	 * Sends an array of values to the provided visualization for rendering.
	 * 
	 * @param map
	 *            2-dimensional array containing the normalised values that should be rendered.
	 * @param vis
	 *            The visualization of the game.
	 */
	public static void visualiseMap(float[][] map, HunterKillerVisualization vis) {
		// Define the ignore color as having an alpha of 0
		Color ignore = Color.GRAY.cpy();
		ignore.a = 0f;
		// Set the value array into the visualisation
		vis.visualise(map, Color.GREEN, Color.BLUE, Color.RED, ignore);
	}

}
