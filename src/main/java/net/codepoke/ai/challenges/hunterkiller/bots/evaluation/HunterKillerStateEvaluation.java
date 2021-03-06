package net.codepoke.ai.challenges.hunterkiller.bots.evaluation;

import static net.codepoke.lib.util.UtilOperations.sigmoid;

import java.util.List;

import com.badlogic.gdx.utils.IntArray;

import lombok.Getter;
import net.codepoke.ai.challenge.hunterkiller.HunterKillerState;
import net.codepoke.ai.challenge.hunterkiller.Map;
import net.codepoke.ai.challenge.hunterkiller.MapLocation;
import net.codepoke.ai.challenge.hunterkiller.Player;
import net.codepoke.ai.challenge.hunterkiller.gameobjects.unit.Unit;
import net.codepoke.lib.util.datastructures.MatrixMap;

public class HunterKillerStateEvaluation {

	private static final double MIN_EVAL = -500000f;
	private static final double MAX_EVAL = 750000f;

	@Getter
	private static double currentMinimumEvaluation = Float.NaN;
	@Getter
	private static double currentMaximumEvaluation = Float.NaN;

	/**
	 * Evaluates a HunterKillerState.
	 * NOTE: This entire method is written from the root player's perspective
	 * 
	 * @param gameState
	 *            The state that should be evaluated.
	 * @param rootPlayerID
	 *            The ID of the root player in the search.
	 * @param gameWinEvaluation
	 *            Reward for winning a game.
	 * @param gameLossEvaluation
	 *            Reward for losing a game.
	 * @param distanceMap
	 *            {@link MatrixMap} containing the distances to the enemy's base for each location on the map.
	 */
	public static double evaluate(HunterKillerState gameState, int rootPlayerID, int gameWinEvaluation, int gameLossEvaluation,
			MatrixMap distanceMap) {
		// Check if we can determine a winner
		int endEvaluation = 0;
		if (gameState.isDone()) {
			// Get the scores from the state
			IntArray scores = gameState.getScores();
			// Determine the winning score
			int winner = -1;
			int winningScore = -1;
			for (int i = 0; i < scores.size; i++) {
				int score = scores.get(i);
				if (score > winningScore) {
					winner = i;
					winningScore = score;
				}
			}
			// Check if the root player won
			endEvaluation = winner == rootPlayerID ? gameWinEvaluation : gameLossEvaluation;
		}

		// We use the context here, because we want to evaluate from the root player's perspective.
		Map gameMap = gameState.getMap();
		Player rootPlayer = gameState.getPlayer(rootPlayerID);

		// Calculate the amount of units the root player has
		List<Unit> units = rootPlayer.getUnits(gameMap);
		int rootUnits = units.size();

		// Calculate the root player's Field-of-View size, relative to the map's size
		// NOTE: expensive calculation
		float rootFoV = rootPlayer.getCombinedFieldOfView(gameMap)
									.size() / (gameMap.getMapHeight() * (float) gameMap.getMapWidth());

		// Determine the maximum distance in our map
		int maxKBDistance = distanceMap.findRange()[1];
		// Find the minimum distance for our units (note that the KB is filled with enemy structures as source)
		int minUnitDistance = maxKBDistance;
		for (Unit unit : units) {
			MapLocation unitLocation = unit.getLocation();
			// Because the distance map is filled with enemy structures as source, lower values are closer.
			int unitDistance = distanceMap.get(unitLocation.getX(), unitLocation.getY());
			if (unitDistance < minUnitDistance)
				minUnitDistance = unitDistance;
		}
		// The farthest unit is a number of steps away from an enemy structure equal to the maximum distance minus
		// its distance
		int unitProgress = maxKBDistance - minUnitDistance;

		// Calculate the difference in score between the root player and other players
		int currentScore = rootPlayer.getScore();
		int scoreDelta = currentScore;
		for (Player player : gameState.getPlayers()) {
			if (player.getID() != rootPlayerID) {
				int opponentDelta = currentScore - player.getScore();
				if (opponentDelta < scoreDelta)
					scoreDelta = opponentDelta;
			}
		}

		double evaluation = endEvaluation + ((double) Math.pow(scoreDelta / 25.0, 3) * 4) + ((double) Math.pow(rootUnits, 3) * 10)
							+ (unitProgress * 1) + (rootFoV);

		// Normalize the evaluation before returning it.
		double normEvaluation = (evaluation - MIN_EVAL) / (MAX_EVAL - MIN_EVAL);

		// Throw this normalized value through a sigmoid, because middle-of-the-pack values are more likely than
		// extremes
		double sigmoidEvaluation = sigmoid(normEvaluation);

		if (Double.isNaN(currentMinimumEvaluation) || sigmoidEvaluation < currentMinimumEvaluation)
			currentMinimumEvaluation = sigmoidEvaluation;
		if (Double.isNaN(currentMaximumEvaluation) || sigmoidEvaluation > currentMaximumEvaluation)
			currentMaximumEvaluation = sigmoidEvaluation;

		return sigmoidEvaluation;
	}

	/**
	 * Calculates the decay that should be applied to an evaluation value based on the progression of the playout
	 * towards the cutoff.
	 * 
	 * @param playoutProgression
	 *            How many rounds the playout has progressed since the start of the search.
	 * @param playoutCutoff
	 *            The number of rounds after which a playout will be cut off.
	 */
	public static float calculateDecay(int playoutProgression, int playoutCutoff) {
		// When progression is equal to the cutoff, decay should be 0.5
		// When progression is zero, decay should be 1.0
		return 0.5f + ((playoutCutoff - playoutProgression) * (0.5f / playoutCutoff));
	}

}
