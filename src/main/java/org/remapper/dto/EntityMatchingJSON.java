package org.remapper.dto;

import org.apache.commons.lang3.tuple.Pair;

import java.util.ArrayList;
import java.util.List;

public class EntityMatchingJSON {

    private List<Result> results;

    public EntityMatchingJSON() {
        this.results = new ArrayList<>();
    }

    public void populateJSON(String repository, String sha1, String url, MatchPair matchPair) {
        Result result = new Result(repository, sha1, url, matchPair);
        results.add(result);
    }

    class Result {
        private String repository;
        private String sha1;
        private String url;
        private List<Entity> matchedEntities;

        public Result(String repository, String sha1, String url, MatchPair matchPair) {
            this.repository = repository;
            this.sha1 = sha1;
            this.url = url;
            this.matchedEntities = new ArrayList<>();
            for (Pair<DeclarationNodeTree, DeclarationNodeTree> pair : matchPair.getMatchedEntities()) {
                Location left = new EntityLocation(pair.getLeft().getEntity());
                Location right = new EntityLocation(pair.getRight().getEntity());
                Entity entity = new Entity(left, right);
                this.matchedEntities.add(entity);
            }

            for (Pair<StatementNodeTree, StatementNodeTree> pair : matchPair.getMatchedStatements()) {
                Location left = new StatementLocation(pair.getLeft().getEntity());
                Location right = new StatementLocation(pair.getRight().getEntity());
                Entity entity = new Entity(left, right);
                this.matchedEntities.add(entity);
            }
        }
    }

    class Entity {
        private final Location leftSideLocation;
        private final Location rightSideLocation;

        public Entity(Location leftSideLocation, Location rightSideLocation) {
            this.leftSideLocation = leftSideLocation;
            this.rightSideLocation = rightSideLocation;
        }
    }

    class EntityLocation extends Location {

        private final String container;
        private final String type;
        private final String name;

        public EntityLocation(EntityInfo entity) {
            super(entity);
            this.container = entity.getContainer();
            this.type = entity.getType().getName();
            this.name = entity.getName();
        }
    }

    class StatementLocation extends Location {

        private final String method;
        private final String type;
        private final String expression;

        public StatementLocation(StatementInfo entity) {
            super(entity);
            this.method = entity.getMethod();
            this.type = entity.getType().getName();
            this.expression = entity.getExpression();
        }
    }

    class Location {
        private final String filePath;
        private final int startLine;
        private final int endLine;
        private final int startColumn;
        private final int endColumn;

        public Location(EntityInfo entity) {
            this.filePath = entity.getLocation().getFilePath();
            this.startLine = entity.getLocation().getStartLine();
            this.endLine = entity.getLocation().getEndLine();
            this.startColumn = entity.getLocation().getStartColumn();
            this.endColumn = entity.getLocation().getEndColumn();
        }

        public Location(StatementInfo entity) {
            this.filePath = entity.getLocation().getFilePath();
            this.startLine = entity.getLocation().getStartLine();
            this.endLine = entity.getLocation().getEndLine();
            this.startColumn = entity.getLocation().getStartColumn();
            this.endColumn = entity.getLocation().getEndColumn();
        }
    }
}
