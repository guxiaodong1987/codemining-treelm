/**
 * 
 */
package codemining.lm.tsg;

import static com.google.common.base.Preconditions.checkArgument;

import java.io.Serializable;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Set;

import codemining.ast.TreeNode;
import codemining.ast.TreeNode.NodeDataPair;
import codemining.util.StatsUtil;

import com.google.common.base.Predicate;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Multiset;
import com.google.common.collect.Multiset.Entry;
import com.google.common.collect.Sets;

/**
 * Compute the tree probability.
 * 
 * @author Miltos Allamanis <m.allamanis@ed.ac.uk>
 * 
 */
public class TreeProbabilityComputer<T extends Serializable> {

	private final Map<T, ? extends Multiset<TreeNode<T>>> grammar;
	private final TSGrammar<T> tsGrammar;
	private final Predicate<NodeDataPair<T>> equalityComparator;
	private final boolean requireAllChildren;
	/**
	 * The default TSGNode matching predicate.
	 */
	public static final Predicate<NodeDataPair<TSGNode>> TSGNODE_MATCHER = new Predicate<NodeDataPair<TSGNode>>() {

		@Override
		public boolean apply(final NodeDataPair<TSGNode> pair) {
			return pair.fromNode.nodeKey == pair.toNode.nodeKey;
		}

	};

	public TreeProbabilityComputer(final TSGrammar<T> tsGrammar,
			final boolean requireAllChildren,
			final Predicate<NodeDataPair<T>> equalityComparator) {
		this.tsGrammar = tsGrammar;
		this.grammar = tsGrammar.grammar;
		this.requireAllChildren = requireAllChildren;
		this.equalityComparator = equalityComparator;
	}

	/**
	 * Given a set of possible productions, compute the probability of the
	 * current node. If none of the productions apply, backoff to a CFG.
	 * 
	 * @param nodeProductionProbabilities
	 * @param current
	 * @param allRuleLog2Probabilities
	 * @param productions
	 */
	private void computeNodeProbabilities(
			final Map<TreeNode<T>, Double> nodeProductionProbabilities,
			final TreeNode<T> current, final Multiset<TreeNode<T>> productions) {
		final List<Double> allRuleLog2Probabilities = Lists.newArrayList();

		for (final Entry<TreeNode<T>> productionEntry : productions.entrySet()) {
			// We need to see if it's a partial match, get all it's end
			// points
			if (requireAllChildren) {
				if (!productionEntry.getElement().partialMatch(current,
						equalityComparator, requireAllChildren)) {
					continue;
				}
			} else {
				if (!productionEntry.getElement().isPartialSupertreeOf(current,
						equalityComparator)) {
					continue;
				}
			}

			final Set<TreeNode<T>> endPoints = getRuleEndPointsInTree(
					productionEntry.getElement(), current);

			double productionLog2Prob = tsGrammar
					.computeRulePosteriorLog2Probability(productionEntry
							.getElement());

			for (final TreeNode<T> subtree : endPoints) {
				checkArgument(nodeProductionProbabilities.containsKey(subtree));
				productionLog2Prob += nodeProductionProbabilities.get(subtree);
			}
			allRuleLog2Probabilities.add(productionLog2Prob);
		}

		// Add the CFG production, only if it isn't already in the possible
		// productions.
		final TreeNode<T> cfgRule = TreeNode.create(current);
		double cfgLogProb = 0;
		for (int i = 0; i < current.nProperties(); i++) {
			final List<TreeNode<T>> childrenForProperty = current
					.getChildrenByProperty().get(i);
			for (final TreeNode<T> child : childrenForProperty) {
				cfgRule.addChildNode(TreeNode.create(child), i);
				cfgLogProb += nodeProductionProbabilities.get(child);
			}
		}
		if (!productions.contains(cfgRule)) {
			final double nodeLogProb = tsGrammar
					.computeRulePosteriorLog2Probability(cfgRule) + cfgLogProb;
			allRuleLog2Probabilities.add(nodeLogProb);
		}

		// log-sum-exp the probabilities of all the probabilities
		final double log2Prob = StatsUtil
				.log2SumOfExponentials(allRuleLog2Probabilities);
		// store log-probability for this node and go to next.
		nodeProductionProbabilities.put(current, log2Prob);

	}

	/**
	 * Get the plan at which the probabilities need to be computed.
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

			final List<List<TreeNode<T>>> children = current
					.getChildrenByProperty();
			for (int i = 0; i < current.nProperties(); i++) {
				final List<TreeNode<T>> childrenForProperty = children.get(i);
				for (final TreeNode<T> child : childrenForProperty) {
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
			Multiset<TreeNode<T>> productions = null;
			for (final Map.Entry<T, ? extends Multiset<TreeNode<T>>> grammarProduction : grammar
					.entrySet()) {
				if (equalityComparator.apply(new NodeDataPair<T>(
						grammarProduction.getKey(), current.getData()))) {
					productions = grammarProduction.getValue();
					break;
				}
			}

			if (productions == null) {
				// We don't know that, so now compute it naively
				double logProb = 0;
				final List<List<TreeNode<T>>> childrenProperties = current
						.getChildrenByProperty();

				for (final List<TreeNode<T>> childrenForProperty : childrenProperties) {
					for (final TreeNode<T> child : childrenForProperty) {
						logProb += nodeProductionProbabilities.get(child);
					}
				}
				nodeProductionProbabilities.put(current, logProb);
			} else {
				computeNodeProbabilities(nodeProductionProbabilities, current,
						productions);
				// Since the rule matching may be partial, this may be wrong.
				// What should we do? TODO

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

		final Deque<TreeNode<T>> ruleStack = new ArrayDeque<TreeNode<T>>();
		final Deque<TreeNode<T>> treeStack = new ArrayDeque<TreeNode<T>>();

		ruleStack.push(rule);
		treeStack.push(tree);

		// Start walking through the rule, until you reach a rule-leaf. Add that
		// to set
		while (!ruleStack.isEmpty()) {
			final TreeNode<T> ruleNode = ruleStack.pop();
			final TreeNode<T> treeNode = treeStack.pop();

			if (ruleNode.isLeaf()) {
				endpoints.add(treeNode);
			} else {
				checkArgument(equalityComparator.apply(new NodeDataPair<T>(
						ruleNode.getData(), treeNode.getData())));

				final List<List<TreeNode<T>>> ruleProperties = ruleNode
						.getChildrenByProperty();
				final List<List<TreeNode<T>>> treeProperties = treeNode
						.getChildrenByProperty();
				final int end = ruleProperties.size();
				for (int propertyId = 0; propertyId < end; propertyId++) {
					final List<TreeNode<T>> ruleChildrenForProperty = ruleProperties
							.get(propertyId);
					final List<TreeNode<T>> treeChildrenForProperty = treeProperties
							.get(propertyId);

					checkArgument((ruleChildrenForProperty.size() == treeChildrenForProperty
							.size() && requireAllChildren)
							|| (!requireAllChildren && ruleChildrenForProperty
									.size() >= treeChildrenForProperty.size()));

					for (int i = 0; i < treeChildrenForProperty.size(); i++) {
						ruleStack.push(ruleChildrenForProperty.get(i));
						treeStack.push(treeChildrenForProperty.get(i));
					}
				}
			}
		}
		return endpoints;
	}
}
