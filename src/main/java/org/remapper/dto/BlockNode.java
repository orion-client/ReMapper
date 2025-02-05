package org.remapper.dto;

import org.eclipse.jdt.core.dom.*;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class BlockNode extends StatementNodeTree {

    public BlockNode(CompilationUnit cu, String filePath, ASTNode node, Map<ASTNode, StatementNodeTree> initializedSNT) {
        super(cu, filePath, node);
        ASTNode parent = node.getParent();
        if (parent instanceof IfStatement) {
            IfStatement statement = (IfStatement) parent;
            if (node == statement.getThenStatement()) {
                super.setBlockType(BlockType.IF);
            } else if (node == statement.getElseStatement()) {
                super.setBlockType(BlockType.ELSE);
            }
            super.setBlockExpression("if(" + statement.getExpression().toString() + ")");
        } else if (parent instanceof TryStatement) {
            TryStatement statement = (TryStatement) parent;
            List<Expression> resources = statement.resources();
            String resource = resources.stream().map(Expression::toString).collect(Collectors.joining("; "));
            if (node == statement.getBody()) {
                super.setBlockType(BlockType.TRY);
            } else if (node == statement.getFinally()) {
                super.setBlockType(BlockType.FINALLY);
            }
            super.setBlockExpression(resource.equals("") ? "try" : "try(" + resource + ")");
        } else if (parent instanceof CatchClause) {
            CatchClause statement = (CatchClause) parent;
            super.setBlockType(BlockType.CATCH);
            super.setBlockExpression("catch(" + statement.getException().toString() + ")");
        } else if (parent instanceof ForStatement) {
            ForStatement statement = (ForStatement) parent;
            List<Expression> initializers = statement.initializers();
            Expression expression = statement.getExpression();
            List<Expression> updaters = statement.updaters();
            String initializer = initializers.stream().map(Expression::toString).collect(Collectors.joining(", "));
            String updater = updaters.stream().map(Expression::toString).collect(Collectors.joining(", "));
            super.setBlockType(BlockType.FOR);
            super.setBlockExpression("for(" + initializer + "; " + (expression == null ? "" : expression.toString()) + "; " + updater + ")");
        } else if (parent instanceof EnhancedForStatement) {
            EnhancedForStatement statement = (EnhancedForStatement) parent;
            SingleVariableDeclaration parameter = statement.getParameter();
            Expression expression = statement.getExpression();
            super.setBlockType(BlockType.ENHANCED);
            super.setBlockExpression("for(" + parameter.getName().getFullyQualifiedName() + ": " + expression.toString() + ")");
        } else if (parent instanceof WhileStatement) {
            WhileStatement statement = (WhileStatement) parent;
            super.setBlockType(BlockType.ENHANCED);
            super.setBlockExpression("while(" + statement.getExpression().toString() + ")");
        } else if (parent instanceof DoStatement) {
            DoStatement statement = (DoStatement) parent;
            super.setBlockType(BlockType.ENHANCED);
            super.setBlockExpression("do(" + statement.getExpression().toString() + ")");
        } else if (parent instanceof MethodDeclaration) {
            super.setMatched();
        } else if (parent instanceof SwitchStatement) {
            SwitchStatement switchStatement = (SwitchStatement) parent;
            List<Statement> statements = switchStatement.statements();
            for (int i = 0; i < statements.size(); i++) {
                Statement current = statements.get(i);
                if (current == node && statements.get(i - 1) instanceof SwitchCase) {
                    StatementNodeTree caseSNT = initializedSNT.get(statements.get(i - 1));
                    if (caseSNT != null) {
                        super.setParent(caseSNT);
                        caseSNT.addChild(this);
                    }
                    super.setBlockType(BlockType.CASE);
                    super.setBlockExpression(statements.get(i - 1).toString());
                }
            }
        } else if (parent instanceof LambdaExpression) {
            super.setBlockType(BlockType.LAMBDA);
            super.setBlockExpression("");
        } else if (parent instanceof AnonymousClassDeclaration) {
            super.setBlockType(BlockType.ANONYMOUS);
            super.setBlockExpression("");
        } else if (parent instanceof Block) {
            super.setBlockType(BlockType.BLOCK);
            super.setBlockExpression("");
        } else {
            super.setBlockType(BlockType.OTHER);
            super.setBlockExpression("");
        }
    }
}
