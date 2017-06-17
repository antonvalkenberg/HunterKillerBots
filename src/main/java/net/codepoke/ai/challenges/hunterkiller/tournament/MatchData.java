package net.codepoke.ai.challenges.hunterkiller.tournament;

import lombok.AllArgsConstructor;
import lombok.Data;
import net.codepoke.ai.challenge.hunterkiller.orders.OrderStatistics;

/**
 * Holds the data on a single match for a bot.
 * 
 * @author Anton Valkenberg (anton.valkenberg@gmail.com)
 *
 */
@Data
@AllArgsConstructor
public class MatchData {

	/**
	 * The name of the bot.
	 */
	public String botName;

	/**
	 * The rank attained by the bot in the match.
	 */
	public int botRank;

	/**
	 * The score attained by the bot in the match.
	 */
	public int botScore;

	/**
	 * The round number of the last round in the match.
	 */
	public int lastRound;

	/**
	 * The statistics on orders issued by the bot.
	 */
	public OrderStatistics stats;

	@Override
	public String toString() {
		return botName + "\t" + botRank + "\t" + botScore + "\t" + lastRound + "\n" + stats.toString();
	}

}
