package org.stjs.generator.writer.expression;

import java.util.Collections;
import java.util.List;

import org.mozilla.javascript.ast.AstNode;
import org.stjs.generator.GenerationContext;
import org.stjs.generator.visitor.TreePathScannerContributors;
import org.stjs.generator.visitor.VisitorContributor;

import com.sun.source.tree.NewArrayTree;

public class NewArrayWriter implements VisitorContributor<NewArrayTree, List<AstNode>, GenerationContext> {

	@Override
	public List<AstNode> visit(TreePathScannerContributors<List<AstNode>, GenerationContext> visitor, NewArrayTree tree, GenerationContext p,
			List<AstNode> prev) {
		// Java arrays are not supported
		assert true;
		return Collections.<AstNode>emptyList();
	}
}