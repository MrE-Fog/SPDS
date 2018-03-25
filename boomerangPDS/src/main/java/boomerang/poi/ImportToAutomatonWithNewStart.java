package boomerang.poi;

import boomerang.jimple.Field;
import boomerang.jimple.Statement;
import boomerang.jimple.Val;
import sync.pds.solver.nodes.INode;
import sync.pds.solver.nodes.Node;
import wpds.impl.Transition;
import wpds.impl.Weight;
import wpds.impl.WeightedPAutomaton;
import wpds.interfaces.WPAStateListener;

public class ImportToAutomatonWithNewStart<W extends Weight> extends WPAStateListener<Field, INode<Node<Statement, Val>>, W> {
	
	private final WeightedPAutomaton<Field, INode<Node<Statement, Val>>, W> aut;
	private final INode<Node<Statement, Val>> newStart;

	public ImportToAutomatonWithNewStart(WeightedPAutomaton<Field, INode<Node<Statement, Val>>, W> aut, INode<Node<Statement, Val>> start, INode<Node<Statement, Val>> newStart) {
		super(start);
		this.aut = aut;
		this.newStart = newStart;
	}

	@Override
	public void onOutTransitionAdded(Transition<Field, INode<Node<Statement, Val>>> t, W w,
			WeightedPAutomaton<Field, INode<Node<Statement, Val>>, W> weightedPAutomaton) {
		aut.addTransition(
				new Transition<Field, INode<Node<Statement, Val>>>(newStart, t.getLabel(), t.getTarget()));
	}

	@Override
	public void onInTransitionAdded(Transition<Field, INode<Node<Statement, Val>>> t, W w,
			WeightedPAutomaton<Field, INode<Node<Statement, Val>>, W> weightedPAutomaton) {
	}

	@Override
	public int hashCode() {
		final int prime = 31;
		int result = super.hashCode();
		result = prime * result + ((aut == null) ? 0 : aut.hashCode());
		result = prime * result + ((newStart == null) ? 0 : newStart.hashCode());
		return result;
	}

	@Override
	public boolean equals(Object obj) {
		if (this == obj)
			return true;
		if (!super.equals(obj))
			return false;
		if (getClass() != obj.getClass())
			return false;
		ImportToAutomatonWithNewStart other = (ImportToAutomatonWithNewStart) obj;
		if (aut == null) {
			if (other.aut != null)
				return false;
		} else if (!aut.equals(other.aut))
			return false;
		if (newStart == null) {
			if (other.newStart != null)
				return false;
		} else if (!newStart.equals(other.newStart))
			return false;
		return true;
	}

	
}