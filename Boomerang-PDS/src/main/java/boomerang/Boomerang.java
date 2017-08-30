package boomerang;

import java.util.Collection;
import java.util.Set;

import com.beust.jcommander.internal.Sets;
import com.google.common.base.Optional;
import com.google.common.collect.HashMultimap;
import com.google.common.collect.Multimap;

import boomerang.jimple.Field;
import boomerang.jimple.Statement;
import boomerang.jimple.Val;
import boomerang.poi.AbstractPOI;
import boomerang.solver.AbstractBoomerangSolver;
import boomerang.solver.BackwardBoomerangSolver;
import boomerang.solver.ForwardBoomerangSolver;
import heros.utilities.DefaultValueMap;
import soot.SootMethod;
import soot.Unit;
import soot.Value;
import soot.jimple.AssignStmt;
import soot.jimple.InstanceFieldRef;
import soot.jimple.NewExpr;
import soot.jimple.NullConstant;
import soot.jimple.Stmt;
import soot.jimple.toolkits.ide.icfg.BackwardsInterproceduralCFG;
import soot.jimple.toolkits.ide.icfg.BiDiInterproceduralCFG;
import sync.pds.solver.EmptyStackWitnessListener;
import sync.pds.solver.SyncPDSUpdateListener;
import sync.pds.solver.WitnessNode;
import sync.pds.solver.nodes.GeneratedState;
import sync.pds.solver.nodes.INode;
import sync.pds.solver.nodes.Node;
import sync.pds.solver.nodes.SingleNode;
import sync.pds.weights.SetDomain;
import wpds.impl.PushRule;
import wpds.impl.Transition;
import wpds.impl.Weight;
import wpds.interfaces.WPAUpdateListener;

public abstract class Boomerang {
	private final DefaultValueMap<Query, AbstractBoomerangSolver> queryToSolvers = new DefaultValueMap<Query, AbstractBoomerangSolver>() {
		@Override
		protected AbstractBoomerangSolver createItem(Query key) {
			if (key instanceof BackwardQuery)
				return createBackwardSolver((BackwardQuery) key);
			else
				return createForwardSolver((ForwardQuery) key);
		}
	};
	private BackwardsInterproceduralCFG bwicfg;
	private Multimap<AllocAtStmt, Node<Statement, Val>> allAllocationSiteAtFieldRead = HashMultimap.create();
	private Multimap<Stmt, Node<Statement, Val>> activeAllocationSiteAtFieldRead = HashMultimap.create();
	private Collection<ForwardQuery> forwardQueries = Sets.newHashSet();
	private Collection<BackwardQuery> backwardQueries = Sets.newHashSet();
	private Multimap<BackwardQuery, ForwardQuery> backwardToForwardQueries = HashMultimap.create();
	private Multimap<AssignStmt, BackwardQuery> backwardSolverAtFieldWrite = HashMultimap.create();
	private DefaultValueMap<FieldWritePOI, FieldWritePOI> fieldWrites = new DefaultValueMap<FieldWritePOI, FieldWritePOI>() {
		@Override
		protected FieldWritePOI createItem(FieldWritePOI key) {
			return key;
		}
	};

	protected AbstractBoomerangSolver createBackwardSolver(final BackwardQuery key) {
		BackwardBoomerangSolver solver = new BackwardBoomerangSolver(bwicfg());
		solver.registerListener(new SyncPDSUpdateListener<Statement, Val, Field>() {
			@Override
			public void onReachableNodeAdded(WitnessNode<Statement, Val, Field> node) {
				Optional<Stmt> optUnit = node.stmt().getUnit();
				if (optUnit.isPresent()) {
					Stmt stmt = optUnit.get();
					if (stmt instanceof AssignStmt) {
						AssignStmt as = (AssignStmt) stmt;
						if (node.fact().value().equals(as.getLeftOp()) && isAllocationVal(as.getRightOp())) {
							ForwardQuery forwardQuery = new ForwardQuery(node.stmt(),
									new Val(as.getLeftOp(), icfg().getMethodOf(stmt)));
							backwardToForwardQueries.put(key, forwardQuery);
							addForwardQuery(forwardQuery);
						}

						if (as.getRightOp() instanceof InstanceFieldRef) {
							InstanceFieldRef ifr = (InstanceFieldRef) as.getRightOp();
							handleFieldRead(node, ifr, as, key);
						}
					}
				}
			}
		});
		return solver;
	}

	protected AbstractBoomerangSolver createForwardSolver(final ForwardQuery sourceQuery) {
		ForwardBoomerangSolver solver = new ForwardBoomerangSolver(icfg());
		solver.registerListener(new SyncPDSUpdateListener<Statement, Val, Field>() {
			@Override
			public void onReachableNodeAdded(WitnessNode<Statement, Val, Field> node) {
				Optional<Stmt> optUnit = node.stmt().getUnit();
				if (optUnit.isPresent()) {
					Stmt stmt = optUnit.get();
					if (stmt instanceof AssignStmt) {
						AssignStmt as = (AssignStmt) stmt;
						if (as.getLeftOp() instanceof InstanceFieldRef) {
							InstanceFieldRef ifr = (InstanceFieldRef) as.getLeftOp();
							handleFieldWrite(node, ifr, as, sourceQuery);
						}
						if (as.getRightOp() instanceof InstanceFieldRef) {
							InstanceFieldRef ifr = (InstanceFieldRef) as.getRightOp();
							attachHandlerFieldRead(node, ifr, as, sourceQuery);
						}
					}
				}
			}
		});
		return solver;
	}

	protected void handleFieldRead(WitnessNode<Statement, Val, Field> node, final InstanceFieldRef ifr,
			final AssignStmt as, BackwardQuery sourceQuery) {
		if (node.fact().value().equals(as.getLeftOp())) {
			final BackwardQuery backwardQuery = new BackwardQuery(node.stmt(),
					new Val(ifr.getBase(), icfg().getMethodOf(as)));
			backwardSolverAtFieldWrite.put(as, sourceQuery);
			addBackwardQuery(backwardQuery, new EmptyStackWitnessListener<Statement, Val>() {
				@Override
				public void witnessFound(Node<Statement, Val> allocation) {
					if (activeAllocationSiteAtFieldRead.put(as, allocation)) {
						Collection<Node<Statement, Val>> aliases = allAllocationSiteAtFieldRead
								.get(new AllocAtStmt(allocation, as));
						for (Node<Statement, Val> alias : aliases) {
							injectBackwardAlias(alias, as, new Val(ifr.getBase(), icfg().getMethodOf(as)),
									new Field(ifr.getField()), backwardQuery);
						}
					}
				}
			});
		}
	}

	public static boolean isAllocationVal(Value val) {
		return val instanceof NullConstant || val instanceof NewExpr;
	}

	protected void handleFieldWrite(WitnessNode<Statement, Val, Field> node, final InstanceFieldRef ifr,
			final AssignStmt as, final ForwardQuery sourceQuery) {
		BackwardQuery backwardQuery = new BackwardQuery(node.stmt(),
				new Val(ifr.getBase(), icfg().getMethodOf(as)));
		Field field = new Field(ifr.getField());
		if (node.fact().value().equals(as.getRightOp())) {
			addBackwardQuery(backwardQuery, new EmptyStackWitnessListener<Statement, Val>() {
				@Override
				public void witnessFound(Node<Statement, Val> alloc) {
				}
			});
			fieldWrites.getOrCreate(new FieldWritePOI(backwardQuery.asNode(), field, as)).addFlowAllocation(sourceQuery);
		}
		if (node.fact().value().equals(ifr.getBase())) {
			fieldWrites.getOrCreate(new FieldWritePOI(backwardQuery.asNode(),field,as)).addBaseAllocation(sourceQuery);
		}
	}



	private void injectAliasWithStack(INode<Node<Statement, Val>> alias, AssignStmt as, Field label,
			Query sourceQuery) {
		// System.out.println("INJECTION " + alias + as + ifr);
		for (Unit succ : icfg().getSuccsOf(as)) {
			// TODO Why don't we need succ here?
			Node<Statement, Val> sourceNode = new Node<Statement, Val>(new Statement(as, icfg().getMethodOf(as)),
					new Val(as.getRightOp(), icfg().getMethodOf(as)));
			SetDomain<Field, Statement, Val> one = SetDomain.<Field, Statement, Val> one();
			INode<Node<Statement, Val>> source = new SingleNode<Node<Statement, Val>>(sourceNode);
			Node<Statement, Val> targetNode = new Node<Statement, Val>(
					new Statement((Stmt) succ, icfg().getMethodOf(as)), alias.fact().fact());
			INode<Node<Statement, Val>> target = new SingleNode<Node<Statement, Val>>(targetNode);
			queryToSolvers.getOrCreate(sourceQuery)
					.injectFieldRule(new PushRule<Field, INode<Node<Statement, Val>>, Weight<Field>>(source,
							Field.wildcard(), alias, label, Field.wildcard(), one));
		}
	}

	private void attachHandlerFieldRead(final WitnessNode<Statement, Val, Field> node, final InstanceFieldRef ifr,
			final AssignStmt fieldRead, ForwardQuery sourceQuery) {
		queryToSolvers.getOrCreate(sourceQuery)
				.addFieldAutomatonListener(new WPAUpdateListener<Field, INode<Node<Statement, Val>>, Weight<Field>>() {

					@Override
					public void onAddedTransition(Transition<Field, INode<Node<Statement, Val>>> t) {
						if (t.getStart() instanceof GeneratedState)
							return;
						if (!t.getStart().fact().equals(node.asNode()))
							return;
						if (!(t.getTarget() instanceof GeneratedState)) {
							Node<Statement, Val> target = t.getTarget().fact();
							Node<Statement, Val> alloc = new Node<Statement, Val>(target.stmt(), target.fact());

							if (activeAllocationSiteAtFieldRead.get(fieldRead).contains(target)) {
								// TODO where to get the backward query for that
								// requires this flow.
								for (BackwardQuery backwardSourceQuery : backwardSolverAtFieldWrite.get(fieldRead)) {
									injectBackwardAlias(node.asNode(), fieldRead,
											new Val(ifr.getBase(), icfg().getMethodOf(fieldRead)),
											new Field(ifr.getField()), backwardSourceQuery);
								}
							} else {
								System.out.println("Queuing alloc: " + alloc + fieldRead);
								allAllocationSiteAtFieldRead.put(new AllocAtStmt(alloc, fieldRead), node.asNode());
							}
						} else {
							// TODO only do so, if we have an alias
							// System.out.println("NOT WITNESSS ALISAES FIELD "
							// + t);
							// System.out.println("asdasdINJECTION source" +
							// node.asNode() + "\n \t at "+as + ifr);
						}
					}

					@Override
					public void onWeightAdded(Transition<Field, INode<Node<Statement, Val>>> t, Weight<Field> w) {
						// TODO Auto-generated method stub

					}
				});
	}

	private void injectForwardAlias(Node<Statement, Val> alias, AssignStmt as, Val base, Field ifr, Query sourceQuery) {
		if (alias.fact().equals(base))
			return;
		System.out.println("Injecting forward alias " + alias + ifr);
		for (Unit succ : icfg().getSuccsOf(as)) {
			Node<Statement, Val> sourceNode = new Node<Statement, Val>(new Statement(as, icfg().getMethodOf(as)),
					new Val(as.getRightOp(), icfg().getMethodOf(as)));
			Node<Statement, Val> targetNode = new Node<Statement, Val>(
					new Statement((Stmt) succ, icfg().getMethodOf(succ)), alias.fact());
			queryToSolvers.getOrCreate(sourceQuery).injectFieldRule(sourceNode, ifr, targetNode);
		}
	}

	private void injectBackwardAlias(Node<Statement, Val> alias, AssignStmt as, Val base, Field ifr,
			BackwardQuery backwardQuery) {
		if (alias.fact().equals(base))
			return;
		System.out.println("Injecting backward alias " + alias + ifr);
		for (Unit succ : bwicfg().getSuccsOf(as)) {
			Node<Statement, Val> source = new Node<Statement, Val>(new Statement(as, icfg().getMethodOf(as)),
					new Val(as.getLeftOp(), icfg().getMethodOf(as)));
			Node<Statement, Val> target = new Node<Statement, Val>(new Statement((Stmt) succ, icfg().getMethodOf(succ)),
					alias.fact());
			queryToSolvers.getOrCreate(backwardQuery).injectFieldRule(source, ifr, target);
		}
	}

	private BiDiInterproceduralCFG<Unit, SootMethod> bwicfg() {
		if (bwicfg == null)
			bwicfg = new BackwardsInterproceduralCFG(icfg());
		return bwicfg;
	}

	protected void addForwardQuery(ForwardQuery query) {
		forwardSolve(query);
	}

	public void addBackwardQuery(final BackwardQuery backwardQueryNode,
			EmptyStackWitnessListener<Statement, Val> listener) {
		backwardSolve(backwardQueryNode);
		for (ForwardQuery fw : backwardToForwardQueries.get(backwardQueryNode)) {
			queryToSolvers.getOrCreate(fw).synchedEmptyStackReachable(backwardQueryNode.asNode(), listener);
		}
	}

	public void solve(Query query) {
		if (query instanceof ForwardQuery) {
			forwardSolve((ForwardQuery) query);
		}
		if (query instanceof BackwardQuery) {
			backwardSolve((BackwardQuery) query);
		}
	}

	private void backwardSolve(BackwardQuery query) {
		System.out.println("Backward solving query: " + query);
		backwardQueries.add(query);
		Optional<Stmt> unit = query.asNode().stmt().getUnit();
		AbstractBoomerangSolver solver = queryToSolvers.getOrCreate(query);
		if (unit.isPresent()) {
			for (Unit succ : new BackwardsInterproceduralCFG(icfg()).getSuccsOf(unit.get())) {
				solver.solve(query.asNode(), new Node<Statement, Val>(
						new Statement((Stmt) succ, icfg().getMethodOf(succ)), query.asNode().fact()));
			}
		}
	}

	private void forwardSolve(ForwardQuery query) {
		Optional<Stmt> unit = query.asNode().stmt().getUnit();
		System.out.println("Forward solving query: " + query);
		forwardQueries.add(query);
		AbstractBoomerangSolver solver = queryToSolvers.getOrCreate(query);
		if (unit.isPresent()) {
			for (Unit succ : icfg().getSuccsOf(unit.get())) {
				Node<Statement, Val> source = new Node<Statement, Val>(
						new Statement((Stmt) succ, icfg().getMethodOf(succ)), query.asNode().fact());
				solver.solve(query.asNode(), source);
			}
		}
		solver.getFieldAutomaton().addFinalState(new SingleNode<Node<Statement, Val>>(query.asNode()));

	}

	private class FieldWritePOI extends AbstractPOI<Statement, Val, Field> {

		private Field field;
		private AssignStmt fieldWriteStatement;

		public FieldWritePOI(Node<Statement,Val> node, Field field, AssignStmt fieldWriteStatement) {
			super(node);
			this.field = field;
			this.fieldWriteStatement = fieldWriteStatement;
		}

		@Override
		public void execute(final ForwardQuery baseAllocation, final ForwardQuery flowAllocation) {
			System.out.println(baseAllocation + " +" +  flowAllocation + "   " );
			System.out.println(getNode());
			queryToSolvers.get(baseAllocation).getFieldAutomaton().registerListener(new WPAUpdateListener<Field, INode<Node<Statement,Val>>, Weight<Field>>() {

				@Override
				public void onAddedTransition(Transition<Field, INode<Node<Statement, Val>>> t) {
					if(t.getTarget() instanceof GeneratedState){
//						injectAliasWithStack(t.getTarget(), fieldWriteStatement, field, flowAllocation);
						System.out.println("HERE" + t);
						return;
					}
					if(t.getTarget().fact().equals(baseAllocation.asNode()) && t.getLabel().equals(Field.empty())){
						injectForwardAlias(t.getStart().fact(),fieldWriteStatement, getNode().fact(), field, flowAllocation);
					}
//					System.out.println(t);	
				}

				@Override
				public void onWeightAdded(Transition<Field, INode<Node<Statement, Val>>> t, Weight<Field> w) {
				}
			});
		}
	}

	public abstract BiDiInterproceduralCFG<Unit, SootMethod> icfg();

	public Collection<? extends Node<Statement, Val>> getForwardReachableStates() {
		Set<Node<Statement, Val>> res = Sets.newHashSet();
		for (Query q : queryToSolvers.keySet()) {
			if (q instanceof ForwardQuery)
				res.addAll(queryToSolvers.getOrCreate(q).getReachedStates());
		}
		return res;
	}

	public void debugOutput() {
		for (Query q : queryToSolvers.keySet()) {
			if (q instanceof ForwardQuery) {
				System.out.println("========================");
				System.out.println(q);
				System.out.println("========================");
				 queryToSolvers.getOrCreate(q).debugOutput();
			}
		}
		// backwardSolver.debugOutput();
		// forwardSolver.debugOutput();
		// for(ForwardQuery fq : forwardQueries){
		// for(Node<Statement, Val> bq : forwardSolver.getReachedStates()){
		// IRegEx<Field> extractLanguage =
		// forwardSolver.getFieldAutomaton().extractLanguage(new
		// SingleNode<Node<Statement,Val>>(bq),new
		// SingleNode<Node<Statement,Val>>(fq.asNode()));
		// System.out.println(bq + " "+ fq +" "+ extractLanguage);
		// }
		// }
		// for(final BackwardQuery bq : backwardQueries){
		// forwardSolver.synchedEmptyStackReachable(bq.asNode(), new
		// WitnessListener<Statement, Val>() {
		//
		// @Override
		// public void witnessFound(Node<Statement, Val> targetFact) {
		// System.out.println(bq + " is allocated at " +targetFact);
		// }
		// });
		// }

		// for(ForwardQuery fq : forwardQueries){
		// System.out.println(fq);
		// System.out.println(Joiner.on("\n\t").join(forwardSolver.getFieldAutomaton().dfs(new
		// SingleNode<Node<Statement,Val>>(fq.asNode()))));
		// }
	}
}