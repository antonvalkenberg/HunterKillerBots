package net.codepoke.ai.challenges.hunterkiller.bots.sorting;

import lombok.NoArgsConstructor;
import lombok.Setter;
import net.codepoke.ai.challenge.hunterkiller.HunterKillerState;
import net.codepoke.ai.challenge.hunterkiller.Map;
import net.codepoke.ai.challenge.hunterkiller.Player;
import net.codepoke.ai.challenge.hunterkiller.gameobjects.Controlled;
import net.codepoke.ai.challenge.hunterkiller.gameobjects.mapfeature.Structure;

import com.badlogic.gdx.utils.IntArray;

@NoArgsConstructor
public class StaticSorting
		implements ControlledObjectSortingStrategy {

	@Setter
	public IntArray staticSorting;

	@Override
	public IntArray sort(HunterKillerState state) {
		Map map = state.getMap();
		Player player = state.getActivePlayer();
		IntArray unitIDs = player.getUnitIDs();
		IntArray structureIDs = player.getStructureIDs();

		IntArray staticOutput = new IntArray();
		// Add any IDs that are still controlled by the player to the sorting first
		for (int i = 0; i < staticSorting.size; i++) {
			int id = staticSorting.get(i);
			if (unitIDs.contains(id)) {
				staticOutput.add(id);
			} else if (structureIDs.contains(id)) {
				Structure structure = (Structure) map.getObject(id);
				if (structure.canSpawnAUnit(state))
					staticOutput.add(id);
			}
		}

		IntArray output = new IntArray();
		// Select all objects controlled by this player and add their ID to the output array
		map.getObjects()
			.select(i -> i != null && !staticOutput.contains(i.getID()) && i instanceof Controlled
							&& ((Controlled) i).isControlledBy(player)
							// Filter out Structures that can't spawn (i.e. have no dimensions to expand)
							&& (!(i instanceof Structure) || ((Structure) i).canSpawnAUnit(state)))
			.forEach(i -> output.add(i.getID()));
		// Randomize the IDs that are not in the static sorting.
		output.shuffle();

		if (output.size > 0) {
			// Add the static and random parts together
			staticOutput.addAll(output);
		}

		return staticOutput;
	}

	@Override
	public void postProcess(IntArray currentOrdering, int nextDimension) {
	}

}
