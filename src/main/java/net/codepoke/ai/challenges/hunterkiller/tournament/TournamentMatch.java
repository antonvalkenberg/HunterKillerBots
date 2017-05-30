package net.codepoke.ai.challenges.hunterkiller.tournament;

import lombok.Getter;
import net.codepoke.ai.GameRules;
import net.codepoke.ai.GameRules.Result;
import net.codepoke.ai.GameRules.Result.Ranking;
import net.codepoke.ai.challenge.hunterkiller.HunterKillerAction;
import net.codepoke.ai.challenge.hunterkiller.HunterKillerRules;
import net.codepoke.ai.challenge.hunterkiller.HunterKillerState;
import net.codepoke.ai.challenge.hunterkiller.HunterKillerStateFactory;
import net.codepoke.ai.challenge.hunterkiller.Player;
import net.codepoke.ai.challenges.hunterkiller.bots.BaseBot;

import com.badlogic.gdx.utils.Array;

/**
 * A single game of HunterKiller between two bots.
 * 
 * @author Anton Valkenberg (anton.valkenberg@gmail.com)
 *
 */
public class TournamentMatch {

	@SuppressWarnings("rawtypes")
	Array<BaseBot> bots;

	@Getter
	MatchData bot0Data;

	@Getter
	MatchData bot1Data;

	GameRules<HunterKillerState, HunterKillerAction> rules;

	@SuppressWarnings("rawtypes")
	public TournamentMatch(BaseBot bot0, BaseBot bot1) {
		this.bots = Array.with(bot0, bot1);
		rules = new HunterKillerRules();
	}

	public void runGame() {
		runGame(0);
	}

	@SuppressWarnings("rawtypes")
	public void runGame(int gameNumber) {

		// Alternate which bot goes first
		BaseBot botA = bots.get(gameNumber % 2);
		BaseBot botB = bots.get((gameNumber + 1) % 2);

		HunterKillerState state = new HunterKillerStateFactory().generateInitialState(	new String[] { botA.getBotName(), botB.getBotName() },
																						null);
		Result result;
		do {
			HunterKillerState stateCopy = state.copy();
			// Preparing the state-copy for the currently active player
			stateCopy.prepare(state.getActivePlayerID());

			// Get the name of the player that is next to act
			String activePlayerName = state.getActivePlayer()
											.getName();

			// TODO: this goes wrong when two bots have the same name, but different implementation (and/or aren't
			// stateless)
			if (activePlayerName.equals(botA.getBotName())) {
				result = rules.handle(state, botA.handle(stateCopy));
			} else if (activePlayerName.equals(botB.getBotName())) {
				result = rules.handle(state, botB.handle(stateCopy));
			} else {
				throw new RuntimeException("Name of the active bot ('" + activePlayerName
											+ "') does not match any bot registered to this match.");
			}

		} while (!result.isFinished() && result.isAccepted());

		for (Ranking ranking : result.getRanking()) {

			Player player = state.getPlayer(ranking.getPlayerNumber());

			// Check which bot in the bots array this is
			if (player.getName()
						.equals(bots.get(0)
									.getBotName())) {
				bot0Data = new MatchData(player.getName(), ranking.getRank(), player.getScore(), state.getCurrentRound());
			} else {
				bot1Data = new MatchData(player.getName(), ranking.getRank(), player.getScore(), state.getCurrentRound());
			}
		}

	}

}
