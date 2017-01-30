package net.codepoke.ai.challenges.hunterkiller.bots;

import static net.codepoke.ai.challenges.hunterkiller.StreamExtensions.stream;

import java.util.List;

import net.codepoke.ai.challenge.hunterkiller.HunterKillerAction;
import net.codepoke.ai.challenge.hunterkiller.HunterKillerState;
import net.codepoke.ai.challenge.hunterkiller.Map;
import net.codepoke.ai.challenge.hunterkiller.Player;
import net.codepoke.ai.challenge.hunterkiller.gameobjects.mapfeature.Structure;
import net.codepoke.ai.challenges.hunterkiller.HunterKillerVisualization;
import net.codepoke.ai.challenges.hunterkiller.InfluenceMaps;
import net.codepoke.ai.network.AIBot;
import net.codepoke.lib.util.datastructures.MatrixMap;

import com.badlogic.gdx.graphics.Color;

/**
 * Represents a bot used for testing purposes.
 * 
 * @author Anton Valkenberg (anton.valkenberg@gmail.com)
 *
 */
public class TestBot
		extends AIBot<HunterKillerState, HunterKillerAction> {

	HunterKillerVisualization visualisation;

	public TestBot(HunterKillerVisualization vis) {
		super("", HunterKillerState.class, HunterKillerAction.class);
		visualisation = vis;
	}

	@Override
	public HunterKillerAction handle(HunterKillerState state) {
		Player player = state.getActivePlayer();
		Map map = state.getMap();

		// Get a list of all structures that
		List<Structure> enemyStructures = stream(map, Structure.class).filter(i -> !i.isControlledBy(player))
																		.toList();

		// Create a map for the distances to enemy structures
		MatrixMap distancemap = InfluenceMaps.createMap_DistanceToStructures(state, enemyStructures);
		float[][] valueMap = InfluenceMaps.convertToValues(distancemap);

		// Set the value array into the visualisation
		Color ignore = Color.GRAY.cpy();
		ignore.a = 0f;
		visualisation.visualise(valueMap, Color.GREEN, Color.BLUE, Color.RED, ignore);

		// Create a random action to return
		return RandomBot.createRandomAction(state);
	}

}
