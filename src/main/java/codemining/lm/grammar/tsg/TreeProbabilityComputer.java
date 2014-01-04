/**
 * 
 */
package codemining.lm.grammar.tsg;

import static com.google.common.base.Preconditions.checkArgument;

import java.io.Serializable;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Set;

import codemining.lm.grammar.tree.TreeNode;
import codemining.lm.grammar.tree.TreeNode.NodePair;
import codemining.util.StatsUtil;

import com.google.common.base.Predicate;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Multiset;
import com.google.common.collect.Multiset.Entry;
import com.google.common.collect.Sets;
import com.google.common.math.DoubleMath;

/**
 * Compute the tree probability.
 * 
 * @author Miltos Allamanis <m.allamanis@ed.ac.uk>
 * 
 */
public class TreeProbabilityComputer<T extends Serializable> {

	private final Map<T, ? extends Multiset<TreeNode<T>>> grammar;
	private final Predicate<NodePair<T>> equalityComparator;
	private final boolean requireAllChildren;
	/**
	 * The default TSGNode matching predicate.
	 */
	public static final Predicate<NodePair<TSGNode>> TSGNODE_MATCHER = new Predicate<NodePair<TSGNode>>() {

		@Override
		public boolean apply(final NodePair<TSGNode> pair) {
			return pair.fromNode.getData().nodeKey == pair.toNode.getData().nodeKey;
		}

	};

	public TreeProbabilityComputer(
			final Map<T, ? extends Multiset<TreeNode<T>>> grammar,
			final boolean requireAllChildren,
			final Predicate<NodePair<T>> equalityComparator) {
		this.grammar = grammar;
		this.requireAllChildren = requireAllChildren;
		this.equalityComparator = equalityComparator;
	}

	/**
	 * Given a set of possible productions, compute the probability of the
	 * current node.
	 * 
	 * @param nodeProductionProbabilities
	 * @param current
	 * @param allRuleLog2Probabilities
	 * @param possibleProductions
	 */
	private void computeNodeProbabilities(
			final Map<TreeNode<T>, Double> nodeProductionProbabilities,
			final TreeNode<T> current,
			final Multiset<TreeNode<T>> possibleProductions) {
		final List<Double> allRuleLog2Probabilities = Lists.newArrayList();
		for (final Entry<TreeNode<T>> productionEntry : possibleProductions
				.entrySet()) {
			// We need to see if it's a partial match, get all it's end
			// points
			if (!productionEntry.getElement().partialMatch(current,
					equalityComparator, requireAllChildren)) {
				continue;
			}

			final Set<TreeNode<T>> endPoints = getRuleEndPointsInTree(
					productionEntry.getElement(), current);

			double productionLog2Prob = DoubleMath.log2(productionEntry
					.getCount()) - DoubleMath.log2(possibleProductions.size());
			for (final TreeNode<T> subtree : endPoints) {
				checkArgument(nodeProductionProbabilities.containsKey(subtree));
				productionLog2Prob += nodeProductionProbabilities.get(subtree);
			}
			allRuleLog2Probabilities.add(productionLog2Prob);
		}

		if (allRuleLog2Probabilities.isEmpty()) {
			nodeProductionProbabilities.put(current, Double.NEGATIVE_INFINITY);
		} else {
			// log-sum-exp the probabilities of all the exponentials
			final double logProb = StatsUtil
					.logSumOfExponentials(allRuleLog2Probabilities);
			// store log-probability for this node and go to next.
			nodeProductionProbabilities.put(current, logProb);
		}
	}

	/**
	 * Get the plan at which the probabilites need to be computed.
	 * 
	 * @param tree
	 * @return
	 */
	public List<TreeNode<T>> getComputePlan(final TreeNode<T> tree) {
		// Get order of prob computation (bottom nodes, first. Topological
		// order)
		final List<TreeNode<T>> ordered = Lists.newArrayList();
		final Deque<TreeNode<T>> stack = new ArrayDeque<TreeNode<T>>();

		stack.push(tree);
		while (!stack.isEmpty()) {
			final TreeNode<T> current = stack.pop();
			ordered.add(current);

			final List<List<TreeNode<T>>> childrenByProperty = current
					.getChildrenByProperty();
			for (int i = 0; i < current.nProperties(); i++) {
				final List<TreeNode<T>> children = childrenByProperty.get(i);
				for (final TreeNode<T> child : children) {
					stack.push(child);
				}
			}
		}
		return ordered;
	}

	/**
	 * Get the log2-probability of the tree in this TSG.
	 * 
	 * @param tree
	 * @param onPartialTree
	 * @return
	 */
	public double getLog2ProbabilityOf(final TreeNode<T> tree) {
		final List<TreeNode<T>> ordered = getComputePlan(tree);

		final Map<TreeNode<T>, Double> nodeProductionProbabilities = Maps
				.newIdentityHashMap();

		// Using this order compute the probability at this node:
		for (int i = ordered.size() - 1; i >= 0; i--) {
			final TreeNode<T> current = ordered.get(i);

			if (current.isLeaf()) {
				nodeProductionProbabilities.put(current, 0.);
				continue;
			}

			// For this node, get all the rules that partially match, along with
			// the
			// nodes they terminate in (if any)
			// sum the log probabilities for the rule and the lower nodes
			final Multiset<TreeNode<T>> possibleProductions = grammar
					.get(current.getData());

			if (possibleProductions == null) {
				// We don't know that, so now compute it naively
				double logProb = 0;
				final List<List<TreeNode<T>>> childrenProperties = current
						.getChildrenByProperty();

				for (final List<TreeNode<T>> children : childrenProperties) {
					for (final TreeNode<T> child : children) {
						logProb += nodeProductionProbabilities.get(child);
					}
				}
				nodeProductionProbabilities.put(current, logProb);
			} else {
				computeNodeProbabilities(nodeProductionProbabilities, current,
						possibleProductions);
			}

		}

		return nodeProductionProbabilities.get(tree);
	}

	/**
	 * Returns a set of endpoint of this rule, given the tree.
	 * 
	 * @param rule
	 * @param tree
	 * @return
	 */
	private Set<TreeNode<T>> getRuleEndPointsInTree(final TreeNode<T> rule,
			final TreeNode<T> tree) {
		final Set<TreeNode<T>> endpoints = Sets.newIdentityHashSet();

		final Deque<TreeNode<T>> ruleVisit = new ArrayDeque<TreeNode<T>>();
		final Deque<TreeNode<T>> treeVisit = new ArrayDeque<TreeNode<T>>();

		ruleVisit.push(rule);
		treeVisit.push(tree);

		// Start walking through the rule, until you reach a rule-leaf. Add that
		// to set
		while (!ruleVisit.isEmpty()) {
			final TreeNode<T> ruleNode = ruleVisit.pop();
			final TreeNode<T> treeNode = treeVisit.pop();

			if (ruleNode.isLeaf()) {
				endpoints.add(treeNode);
			} else {
				checkArgument(equalityComparator.apply(new NodePair<T>(
						ruleNode, treeNode)));

				final List<List<TreeNode<T>>> ruleProperties = ruleNode
						.getChildrenByProperty();
				final List<List<TreeNode<T>>> treeProperties = treeNode
						.getChildrenByProperty();
				final int end = ruleProperties.size();
				for (int propertyId = 0; propertyId < end; propertyId++) {
					final List<TreeNode<T>> rulePropertyChildren = ruleProperties
							.get(propertyId);
					final List<TreeNode<T>> treePropertyChildren = treeProperties
							.get(propertyId);
					checkArgument(rulePropertyChildren.size() <= treePropertyChildren
							.size());

					for (int i = 0; i < rulePropertyChildren.size(); i++) {
						ruleVisit.push(rulePropertyChildren.get(i));
						treeVisit.push(treePropertyChildren.get(i));
					}
				}
			}
		}
		return endpoints;
	}
}