package net.codepoke.ai.challenges.hunterkiller.bots.sorting;

import net.codepoke.ai.challenge.hunterkiller.HunterKillerState;
import net.codepoke.ai.challenge.hunterkiller.Map;
import net.codepoke.ai.challenge.hunterkiller.Player;
import net.codepoke.ai.challenge.hunterkiller.gameobjects.Controlled;
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
		Player player = state.getActivePlayer();
		Map map = state.getMap();
		IntArray output = new IntArray();
		// Select all objects controlled by this player and add their ID to the output array
		map.getObjects()
			.select(i -> i instanceof Controlled && ((Controlled) i).isControlledBy(player)
			// Filter out Structures that can't spawn (i.e. have no dimensions to expand)
							&& (!(i instanceof Structure) || ((Structure) i).canSpawnAUnit(state)))
			.forEach(i -> output.add(i.getID()));

		// Randomize the array
		output.shuffle();

		return output;
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
