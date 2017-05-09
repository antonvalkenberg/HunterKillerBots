package net.codepoke.ai.challenges.hunterkiller.bots.sorting;

import lombok.NoArgsConstructor;
import lombok.Setter;
import net.codepoke.ai.challenge.hunterkiller.HunterKillerState;
import net.codepoke.ai.challenge.hunterkiller.Map;
import net.codepoke.ai.challenge.hunterkiller.Player;
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

		IntArray controlledIDs = new IntArray(unitIDs);
		// Only add structures that can spawn a unit
		for (int i = 0; i < structureIDs.size; i++) {
			Structure structure = (Structure) map.getObject(structureIDs.get(i));
			if (structure.canSpawnAUnit(state))
				controlledIDs.add(structureIDs.get(i));
		}

		// Create the static part
		IntArray staticOutput = new IntArray();
		for (int i = 0; i < staticSorting.size; i++) {
			int id = staticSorting.get(i);
			// If this static ID is still in the controlled IDs list, add it
			if (controlledIDs.contains(id))
				staticOutput.add(id);
		}

		// Create the random part
		IntArray randomOutput = new IntArray();
		for (int i = 0; i < controlledIDs.size; i++) {
			int id = controlledIDs.get(i);
			// If this controlled ID is not in the static list, add it
			if (!staticOutput.contains(id))
				randomOutput.add(id);
		}
		// Randomize the random part
		randomOutput.shuffle();

		if (randomOutput.size > 0) {
			// Add the static and random parts together
			staticOutput.addAll(randomOutput);
		}

		return staticOutput;
	}

	@Override
	public void postProcess(IntArray currentOrdering, int nextDimension) {
	}

}
