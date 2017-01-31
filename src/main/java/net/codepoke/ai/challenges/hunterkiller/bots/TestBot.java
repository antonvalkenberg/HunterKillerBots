package net.codepoke.ai.challenges.hunterkiller.bots;

import net.codepoke.ai.challenge.hunterkiller.HunterKillerAction;
import net.codepoke.ai.challenge.hunterkiller.HunterKillerState;
import net.codepoke.ai.challenges.hunterkiller.HunterKillerVisualization;
import net.codepoke.ai.challenges.hunterkiller.InfluenceMaps;
import net.codepoke.ai.challenges.hunterkiller.InfluenceMaps.KnowledgeBase;
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
	KnowledgeBase kb;
	private static final String KNOWLEDGE_LAYER_DISTANCE_TO_ENEMY_STRUCTURE = "distance nearest enemy structure";

	public TestBot(HunterKillerVisualization vis) {
		super("", HunterKillerState.class, HunterKillerAction.class);
		visualisation = vis;

		// Create the knowledge-base that we will be using
		kb = new KnowledgeBase();
		kb.put(KNOWLEDGE_LAYER_DISTANCE_TO_ENEMY_STRUCTURE, InfluenceMaps::calculateDistanceToEnemyStructures);
	}

	@Override
	public HunterKillerAction handle(HunterKillerState state) {

		// Update the distances to enemy structures
		// TODO: only do this on event trigger
		kb.get(KNOWLEDGE_LAYER_DISTANCE_TO_ENEMY_STRUCTURE)
			.update(state);

		// Get the distance map
		MatrixMap distancemap = kb.get(KNOWLEDGE_LAYER_DISTANCE_TO_ENEMY_STRUCTURE)
									.getMap();
		float[][] valueMap = InfluenceMaps.convertToValues(distancemap);

		// Set the value array into the visualisation
		Color ignore = Color.GRAY.cpy();
		ignore.a = 0f;
		visualisation.visualise(valueMap, Color.GREEN, Color.BLUE, Color.RED, ignore);

		// Create a random action to return
		return RandomBot.createRandomAction(state);
	}

}
