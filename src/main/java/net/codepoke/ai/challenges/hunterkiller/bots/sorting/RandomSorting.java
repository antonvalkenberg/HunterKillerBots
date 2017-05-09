package net.codepoke.ai.challenges.hunterkiller.bots.sorting;

import net.codepoke.ai.challenge.hunterkiller.HunterKillerState;
import net.codepoke.ai.challenge.hunterkiller.Map;
import net.codepoke.ai.challenge.hunterkiller.Player;
import net.codepoke.ai.challenge.hunterkiller.gameobjects.mapfeature.Structure;

import com.badlogic.gdx.math.MathUtils;
import com.badlogic.gdx.utils.IntArray;

/**
 * Provides the sorting of controlled objects' IDs in a random way.
 * 
 * @author Anton Valkenberg (anton.valkenberg@gmail.com)
 *
 */
public class RandomSorting
		implements ControlledObjectSortingStrategy {

	@Override
	public IntArray sort(HunterKillerState state) {
		Map map = state.getMap();
		Player player = state.getActivePlayer();
		IntArray unitIDs = player.getUnitIDs();
		IntArray structureIDs = player.getStructureIDs();

		IntArray controlledIDs = new IntArray(unitIDs);
		// Only add structures that can spawn a unit
		for (int i = 0; i < structureIDs.size; i++) {
			Structure structure = (Structure) map.getObject(structureIDs.get(i));
			if (structure.canSpawnAUnit(state))
				controlledIDs.add(structureIDs.get(i));
		}

		// Randomize the array
		controlledIDs.shuffle();

		return controlledIDs;
	}

	@Override
	public void postProcess(IntArray currentOrdering, int nextDimensionIndex) {
		shuffle(currentOrdering, nextDimensionIndex);
	}

	/**
	 * Shuffle an array's items, starting from a specific index. Items in front of this index will not be shuffled.
	 * 
	 * @param x
	 *            The array to be shuffled.
	 * @param from
	 *            The index in the array before which items should not be shuffled.
	 */
	public void shuffle(IntArray x, int from) {
		int[] items = x.items;
		for (int i = x.size - 1; i >= from; i--) {
			int ii = MathUtils.random(i - from) + from;
			int temp = items[i];
			items[i] = items[ii];
			items[ii] = temp;
		}
	}

}
