package org.remapper.service;

import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.tuple.Pair;
import org.eclipse.jdt.core.Flags;
import org.eclipse.jdt.core.dom.*;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectLoader;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevTree;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.remapper.dto.*;
import org.remapper.util.ASTParserUtils;
import org.remapper.util.DiceFunction;
import org.remapper.util.EntityUtils;
import org.remapper.util.StringUtils;
import org.remapper.visitor.NodeDeclarationVisitor;
import org.remapper.visitor.NodeUsageVisitor;

import java.io.File;
import java.io.IOException;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.stream.Collectors;

public class SoftwareEntityMatcherService {

    protected void matchEntities(GitService gitService, JDTService jdtService, Repository repository,
                                 RevCommit currentCommit, MatchPair matchPair) throws Exception {
        String commitId = currentCommit.getId().getName();
        Set<String> addedFiles = new LinkedHashSet<>();
        Set<String> deletedFiles = new LinkedHashSet<>();
        Set<String> modifiedFiles = new LinkedHashSet<>();
        Map<String, String> renamedFiles = new LinkedHashMap<>();
        Map<String, String> fileContentsBefore = new LinkedHashMap<>();
        Map<String, String> fileContentsCurrent = new LinkedHashMap<>();
        Map<String, RootNode> fileDNTsBefore = new LinkedHashMap<>();
        Map<String, RootNode> fileDNTsCurrent = new LinkedHashMap<>();
        gitService.fileTreeDiff(repository, currentCommit, addedFiles, deletedFiles, modifiedFiles, renamedFiles);

        RevCommit parentCommit = currentCommit.getParent(0);
        populateFileContents(repository, parentCommit, deletedFiles, fileContentsBefore);
        populateFileContents(repository, parentCommit, modifiedFiles, fileContentsBefore);
        populateFileContents(repository, parentCommit, renamedFiles.keySet(), fileContentsBefore);
        populateFileContents(repository, currentCommit, addedFiles, fileContentsCurrent);
        populateFileContents(repository, currentCommit, modifiedFiles, fileContentsCurrent);
        populateFileContents(repository, currentCommit, new HashSet<>(renamedFiles.values()), fileContentsCurrent);

        populateFileDNTs(jdtService, fileContentsBefore, fileDNTsBefore);
        populateFileDNTs(jdtService, fileContentsCurrent, fileDNTsCurrent);

        pruneUnchangedEntitiesInModifiedFiles(matchPair, modifiedFiles, fileDNTsBefore, fileDNTsCurrent);
        pruneUnchangedEntitiesInRenamedFiles(matchPair, renamedFiles, fileDNTsBefore, fileDNTsCurrent);

        matchByNameAndSignature(matchPair, modifiedFiles, fileDNTsBefore, fileDNTsCurrent);
        matchByDiceCoefficient(matchPair, modifiedFiles, renamedFiles, deletedFiles, addedFiles, fileDNTsBefore, fileDNTsCurrent);

        gitService.checkoutCurrent(repository, commitId);
        String filePath = repository.getWorkTree().getPath();
        populateCurrentDependencies(matchPair, filePath, modifiedFiles, renamedFiles, addedFiles);
        gitService.resetHard(repository);
        gitService.checkoutParent(repository, commitId);
        populateBeforeDependencies(matchPair, filePath, modifiedFiles, renamedFiles, deletedFiles);
        gitService.resetHard(repository);

        fineMatching(matchPair);

        additionalMatchByName(matchPair);
        additionalMatchByDice(matchPair);

        filter(matchPair);
    }

    private void populateFileContents(Repository repository, RevCommit commit,
                                      Set<String> filePaths, Map<String, String> fileContents) throws IOException {
        RevTree parentTree = commit.getTree();
        try (TreeWalk treeWalk = new TreeWalk(repository)) {
            treeWalk.addTree(parentTree);
            treeWalk.setRecursive(true);
            while (treeWalk.next()) {
                String pathString = treeWalk.getPathString();
                if (filePaths.contains(pathString)) {
                    ObjectId objectId = treeWalk.getObjectId(0);
                    ObjectLoader loader = repository.open(objectId);
                    StringWriter writer = new StringWriter();
                    IOUtils.copy(loader.openStream(), writer, StandardCharsets.UTF_8);
                    fileContents.put(pathString, writer.toString());
                }
            }
        }
    }

    private void populateFileDNTs(JDTService jdtService, Map<String, String> fileContents, Map<String, RootNode> fileDNTs) {
        for (String filePath : fileContents.keySet()) {
            RootNode dntBefore = jdtService.parseFileDNT(filePath, fileContents.get(filePath));
            fileDNTs.put(filePath, dntBefore);
        }
    }

    private void pruneUnchangedEntitiesInModifiedFiles(MatchPair matchPair, Set<String> modifiedFiles, Map<String, RootNode> fileDNTsBefore, Map<String, RootNode> fileDNTsCurrent) {
        for (String filePath : modifiedFiles) {
            RootNode dntBefore = fileDNTsBefore.get(filePath);
            RootNode dntCurrent = fileDNTsCurrent.get(filePath);
            pruneUnchangedEntities(matchPair, dntBefore, dntCurrent);
        }
    }

    private void pruneUnchangedEntitiesInRenamedFiles(MatchPair matchPair, Map<String, String> renamedFiles, Map<String, RootNode> fileDNTsBefore, Map<String, RootNode> fileDNTsCurrent) {
        for (String filePath : renamedFiles.keySet()) {
            String renamedFilePath = renamedFiles.get(filePath);
            RootNode dntBefore = fileDNTsBefore.get(filePath);
            RootNode dntCurrent = fileDNTsCurrent.get(renamedFilePath);
            pruneUnchangedEntities(matchPair, filePath, renamedFilePath, dntBefore, dntCurrent);
        }
    }

    private boolean pruneUnchangedEntities(MatchPair matchPair, DeclarationNodeTree dntBefore, DeclarationNodeTree dntCurrent) {
        if (StringUtils.equals(dntBefore.getDeclaration().toString(), dntCurrent.getDeclaration().toString())) {
            if (dntBefore.isRoot() && dntCurrent.isRoot() && dntBefore.hasChildren() && dntCurrent.hasChildren())
                pruneUnchangedEntities(matchPair, dntBefore.getChildren(), dntCurrent.getChildren());
            return true;
        } else if (dntBefore.hasChildren() && dntCurrent.hasChildren())
            pruneUnchangedEntities(matchPair, dntBefore.getChildren(), dntCurrent.getChildren());
        return false;
    }

    private void pruneUnchangedEntities(MatchPair matchPair, List<DeclarationNodeTree> childDNTsBefore, List<DeclarationNodeTree> childDNTsCurrent) {
        List<DeclarationNodeTree> deletionBefore = new ArrayList<>();
        List<DeclarationNodeTree> deletionCurrent = new ArrayList<>();
        for (DeclarationNodeTree node1 : childDNTsBefore) {
            for (DeclarationNodeTree node2 : childDNTsCurrent) {
                if (!node1.equals(node2) || !pruneUnchangedEntities(matchPair, node1, node2))
                    continue;
                deletionBefore.add(node1);
                deletionCurrent.add(node2);
                matchPair.addUnchangedEntity(node1, node2);
                break;
            }
        }
        pruneEntities(childDNTsBefore, childDNTsCurrent, deletionBefore, deletionCurrent);
    }

    private boolean pruneUnchangedEntities(MatchPair matchPair, String filePath, String renamedFilePath, DeclarationNodeTree dntBefore, DeclarationNodeTree dntCurrent) {
        if (StringUtils.equals(dntBefore.getDeclaration().toString(), dntCurrent.getDeclaration().toString())) {
            if (dntBefore.isRoot() && dntCurrent.isRoot() && dntBefore.hasChildren() && dntCurrent.hasChildren())
                pruneUnchangedEntities(matchPair, filePath, renamedFilePath, dntBefore.getChildren(), dntCurrent.getChildren());
            return true;
        } else if (dntBefore.hasChildren() && dntCurrent.hasChildren())
            pruneUnchangedEntities(matchPair, filePath, renamedFilePath, dntBefore.getChildren(), dntCurrent.getChildren());
        return false;
    }

    private void pruneUnchangedEntities(MatchPair matchPair, String filePath, String renamedFilePath, List<DeclarationNodeTree> childDNTsBefore, List<DeclarationNodeTree> childDNTsCurrent) {
        List<DeclarationNodeTree> deletionBefore = new ArrayList<>();
        List<DeclarationNodeTree> deletionCurrent = new ArrayList<>();
        for (DeclarationNodeTree node1 : childDNTsBefore) {
            for (DeclarationNodeTree node2 : childDNTsCurrent) {
                if (!node1.equals(node2, filePath, renamedFilePath) || !pruneUnchangedEntities(matchPair, filePath, renamedFilePath, node1, node2))
                    continue;
                deletionBefore.add(node1);
                deletionCurrent.add(node2);
                addInternalCandidateEntity(matchPair, filePath, renamedFilePath, node1, node2);
                break;
            }
        }
        pruneEntities(childDNTsBefore, childDNTsCurrent, deletionBefore, deletionCurrent);
    }

    private void addInternalCandidateEntity(MatchPair matchPair, String filePath, String renamedFilePath, DeclarationNodeTree node1, DeclarationNodeTree node2) {
        if (node1.equals(node2, filePath, renamedFilePath) &&
                StringUtils.equals(node1.getDeclaration().toString(), node2.getDeclaration().toString())) {
            matchPair.addMatchedEntity(node1, node2);
            if (node1.hasChildren() && node2.hasChildren()) {
                List<DeclarationNodeTree> children1 = node1.getChildren();
                List<DeclarationNodeTree> children2 = node2.getChildren();
                for (DeclarationNodeTree child1 : children1)
                    for (DeclarationNodeTree child2 : children2)
                        addInternalCandidateEntity(matchPair, filePath, renamedFilePath, child1, child2);
            }
        }
    }

    private void pruneEntities(List<DeclarationNodeTree> childDNTsBefore, List<DeclarationNodeTree> childDNTsCurrent, List<DeclarationNodeTree> deletionBefore, List<DeclarationNodeTree> deletionCurrent) {
        DeclarationNodeTree parentBefore = childDNTsBefore.get(0).getParent();
        if (parentBefore instanceof InternalNode)
            ((InternalNode) parentBefore).addDescendants(parentBefore.getChildren());
        parentBefore.getChildren().removeAll(deletionBefore);
        DeclarationNodeTree parentCurrent = childDNTsCurrent.get(0).getParent();
        if (parentCurrent instanceof InternalNode)
            ((InternalNode) parentCurrent).addDescendants(parentCurrent.getChildren());
        parentCurrent.getChildren().removeAll(deletionCurrent);
    }

    private void matchByNameAndSignature(MatchPair matchPair, Set<String> modifiedFiles, Map<String, RootNode> fileDNTsBefore, Map<String, RootNode> fileDNTsCurrent) {
        for (String filePath : modifiedFiles) {
            RootNode dntBefore = fileDNTsBefore.get(filePath);
            RootNode dntCurrent = fileDNTsCurrent.get(filePath);
            if (!dntBefore.hasChildren() || !dntCurrent.hasChildren())
                continue;
            List<DeclarationNodeTree> treeNodesBefore = dntBefore.getAllNodes();
            List<DeclarationNodeTree> treeNodesCurrent = dntCurrent.getAllNodes();
            for (DeclarationNodeTree node1 : treeNodesBefore) {
                for (DeclarationNodeTree node2 : treeNodesCurrent) {
                    if (!node1.equals(node2))
                        continue;
                    if (node1.getType() == EntityType.CLASS || node1.getType() == EntityType.INTERFACE ||
                            node1.getType() == EntityType.ENUM || node1.getType() == EntityType.RECORD ||
                            node1.getType() == EntityType.ANNOTATION_TYPE || node1.getType() == EntityType.INITIALIZER ||
                            node1.getType() == EntityType.ENUM_CONSTANT) {
                        node1.setMatched();
                        node2.setMatched();
                        matchPair.addMatchedEntity(node1, node2);
                        break;
                    } else if (node1.getType() == EntityType.FIELD) {
                        FieldDeclaration fd1 = ((FieldDeclaration) node1.getDeclaration());
                        FieldDeclaration fd2 = ((FieldDeclaration) node2.getDeclaration());
                        if (StringUtils.equals(fd1.getType().toString(), fd2.getType().toString())) {
                            node1.setMatched();
                            node2.setMatched();
                            matchPair.addMatchedEntity(node1, node2);
                            break;
                        }
                    } else if (node1.getType() == EntityType.METHOD) {
                        MethodDeclaration md1 = ((MethodDeclaration) node1.getDeclaration());
                        MethodDeclaration md2 = ((MethodDeclaration) node2.getDeclaration());
                        String pl1 = ((List<SingleVariableDeclaration>) md1.parameters()).stream().
                                map(declaration -> declaration.isVarargs() ? declaration.getType().toString() + "[]" : declaration.getType().toString()).
                                collect(Collectors.joining(","));
                        String pl2 = ((List<SingleVariableDeclaration>) md2.parameters()).stream().
                                map(declaration -> declaration.isVarargs() ? declaration.getType().toString() + "[]" : declaration.getType().toString()).
                                collect(Collectors.joining(","));
                        String tp1 = ((List<TypeParameter>) md1.typeParameters()).stream().
                                map(TypeParameter::toString).
                                collect(Collectors.joining(","));
                        String tp2 = ((List<TypeParameter>) md2.typeParameters()).stream().
                                map(TypeParameter::toString).
                                collect(Collectors.joining(","));
                        if (md1.getReturnType2() == null && md2.getReturnType2() == null && StringUtils.equals(pl1, pl2) &&
                                StringUtils.equals(tp1, tp2)) {
                            node1.setMatched();
                            node2.setMatched();
                            matchPair.addMatchedEntity(node1, node2);
                            break;
                        }
                        if (md1.getReturnType2() != null && md2.getReturnType2() != null &&
                                StringUtils.equals(md1.getReturnType2().toString(), md2.getReturnType2().toString()) &&
                                StringUtils.equals(pl1, pl2) && StringUtils.equals(tp1, tp2)) {
                            node1.setMatched();
                            node2.setMatched();
                            matchPair.addMatchedEntity(node1, node2);
                            break;
                        }
                    } else if (node1.getType() == EntityType.ANNOTATION_MEMBER) {
                        AnnotationTypeMemberDeclaration atd1 = ((AnnotationTypeMemberDeclaration) node1.getDeclaration());
                        AnnotationTypeMemberDeclaration atd2 = ((AnnotationTypeMemberDeclaration) node2.getDeclaration());
                        if (StringUtils.equals(atd1.getType().toString(), atd2.getType().toString())) {
                            node1.setMatched();
                            node2.setMatched();
                            matchPair.addMatchedEntity(node1, node2);
                            break;
                        }
                    }
                }
            }
        }
    }

    private void matchByDiceCoefficient(MatchPair matchPair, Set<String> modifiedFiles, Map<String, String> renamedFiles,
                                        Set<String> deletedFiles, Set<String> addedFiles, Map<String, RootNode> fileDNTsBefore,
                                        Map<String, RootNode> fileDNTsCurrent) {
        for (String filePath : modifiedFiles) {
            RootNode dntBefore = fileDNTsBefore.get(filePath);
            RootNode dntCurrent = fileDNTsCurrent.get(filePath);
            matchLeafNodesByDice(matchPair, dntBefore.getLeafNodes(), dntCurrent.getLeafNodes());
            matchInternalNodesByDice(matchPair, dntBefore.getInternalNodes(), dntCurrent.getInternalNodes());
            matchPair.addDeletedEntities(dntBefore.getUnmatchedNodes());
            matchPair.addAddedEntities(dntCurrent.getUnmatchedNodes());
        }
        for (String filePath : renamedFiles.keySet()) {
            RootNode dntBefore = fileDNTsBefore.get(filePath);
            RootNode dntCurrent = fileDNTsCurrent.get(renamedFiles.get(filePath));
            matchLeafNodesByDice(matchPair, dntBefore.getLeafNodes(), dntCurrent.getLeafNodes());
            matchInternalNodesByDice(matchPair, dntBefore.getInternalNodes(), dntCurrent.getInternalNodes());
            matchPair.addDeletedEntities(dntBefore.getUnmatchedNodes());
            matchPair.addAddedEntities(dntCurrent.getUnmatchedNodes());
        }
        for (String filePath : deletedFiles) {
            RootNode dntBefore = fileDNTsBefore.get(filePath);
            matchPair.addDeletedEntities(dntBefore.getUnmatchedNodes());
        }
        for (String filePath : addedFiles) {
            RootNode dntCurrent = fileDNTsCurrent.get(filePath);
            matchPair.addAddedEntities(dntCurrent.getUnmatchedNodes());
        }
        List<LeafNode> leafDeletion = new ArrayList<>();
        List<InternalNode> internalDeletion = new ArrayList<>();
        matchPair.getDeletedEntities().forEach(dnt -> {
            if (dnt.isLeaf())
                leafDeletion.add((LeafNode) dnt);
            else if (!dnt.isLeaf() && !dnt.isRoot())
                internalDeletion.add((InternalNode) dnt);
        });
        List<LeafNode> leafAddition = new ArrayList<>();
        List<InternalNode> internalAddition = new ArrayList<>();
        matchPair.getAddedEntities().forEach(dnt -> {
            if (dnt.isLeaf())
                leafAddition.add((LeafNode) dnt);
            else if (!dnt.isLeaf() && !dnt.isRoot())
                internalAddition.add((InternalNode) dnt);
        });
        matchLeafNodesByDice(matchPair, leafDeletion, leafAddition);
        matchInternalNodesByDice(matchPair, internalDeletion, internalAddition);
        matchPair.getDeletedEntities().removeAll(matchPair.getCandidateEntitiesLeft());
        matchPair.getAddedEntities().removeAll(matchPair.getCandidateEntitiesRight());
    }

    private void matchLeafNodesByDice(MatchPair matchPair, List<LeafNode> leafNodesBefore, List<LeafNode> leafNodesCurrent) {
        List<EntityPair> entityPairs = new ArrayList<>();
        for (LeafNode leafBefore : leafNodesBefore) {
            for (LeafNode leafCurrent : leafNodesCurrent) {
                if (leafBefore.getType() != leafCurrent.getType())
                    continue;
                double dice = DiceFunction.calculateDice(leafBefore, leafCurrent);
                if (dice < DiceFunction.minDice)
                    continue;
                EntityPair entityPair = new EntityPair(leafBefore, leafCurrent);
                entityPair.setDice(dice);
                entityPairs.add(entityPair);
            }
        }
        addCandidateEntities(matchPair, entityPairs);
    }

    private void matchInternalNodesByDice(MatchPair matchPair, List<InternalNode> internalNodesBefore, List<InternalNode> internalNodesCurrent) {
        List<EntityPair> entityPairs = new ArrayList<>();
        for (InternalNode internalBefore : internalNodesBefore) {
            for (InternalNode internalCurrent : internalNodesCurrent) {
                if (internalBefore.getType() != internalCurrent.getType())
                    continue;
                double dice = DiceFunction.calculateDice(matchPair, internalBefore, internalCurrent);
                if (dice < DiceFunction.minDice)
                    continue;
                EntityPair entityPair = new EntityPair(internalBefore, internalCurrent);
                entityPair.setDice(dice);
                entityPairs.add(entityPair);
            }
        }
        addCandidateEntities(matchPair, entityPairs);
    }

    private void addCandidateEntities(MatchPair matchPair, List<EntityPair> entityPairs) {
        Collections.sort(entityPairs);
        Set<DeclarationNodeTree> existBefore = new HashSet<>();
        Set<DeclarationNodeTree> existCurrent = new HashSet<>();
        for (EntityPair entityPair : entityPairs) {
            DeclarationNodeTree node1 = entityPair.getLeft();
            DeclarationNodeTree node2 = entityPair.getRight();
            if (existBefore.contains(node1) || existCurrent.contains(node2))
                continue;
            existBefore.add(node1);
            existCurrent.add(node2);
            matchPair.addCandidateEntity(node1, node2);
        }
    }

    private void populateEntityDependencies(ProjectParser parser, Map<EntityInfo, List<EntityInfo>> dependencies) {
        for (String filePath : parser.getRelatedJavaFiles()) {
            ASTParser astParser = ASTParserUtils.getASTParser(parser.getSourcepathEntries(), parser.getEncodings());
            try {
                String code = FileUtils.readFileToString(new File(filePath), StandardCharsets.UTF_8);
                astParser.setSource(code.toCharArray());
                CompilationUnit cu = (CompilationUnit) astParser.createAST(null);
                NodeDeclarationVisitor visitor = new NodeDeclarationVisitor();
                cu.accept(visitor);
                List<TypeDeclaration> typeDeclarations = visitor.getTypeDeclarations();
                List<EnumDeclaration> enumDeclarations = visitor.getEnumDeclarations();
                List<AnnotationTypeDeclaration> annotationTypeDeclarations = visitor.getAnnotationTypeDeclarations();
                List<RecordDeclaration> recordDeclarations = visitor.getRecordDeclarations();
                List<Initializer> initializers = visitor.getInitializers();
                List<EnumConstantDeclaration> enumConstantDeclarations = visitor.getEnumConstantDeclarations();
                List<FieldDeclaration> fieldDeclarations = visitor.getFieldDeclarations();
                List<MethodDeclaration> methodDeclarations = visitor.getMethodDeclarations();
                List<AnnotationTypeMemberDeclaration> annotationMemberDeclarations = visitor.getAnnotationMemberDeclarations();
                populateDependencyOnTypeDeclaration(typeDeclarations, dependencies, cu, filePath);
                populateDependencyOnEnumDeclaration(enumDeclarations, dependencies, cu, filePath);
                populateDependencyOnAnnotationTypeDeclaration(annotationTypeDeclarations, dependencies, cu, filePath);
                populateDependencyOnRecordDeclaration(recordDeclarations, dependencies, cu, filePath);
                populateDependencyInInitializers(initializers, dependencies, cu, filePath);
                populateDependencyInFieldDeclaration(fieldDeclarations, dependencies, cu, filePath);
                populateDependencyInMethodDeclaration(methodDeclarations, dependencies, cu, filePath);
                populateDependencyInAnnotationMemberDeclaration(annotationMemberDeclarations, dependencies, cu, filePath);
                populateDependencyInEnumConstant(enumConstantDeclarations, dependencies, cu, filePath);
            } catch (IOException ignored) {
            }
        }
    }

    private void populateDependencyOnTypeDeclaration(List<TypeDeclaration> typeDeclarations, Map<EntityInfo, List<EntityInfo>> dependencies, CompilationUnit cu, String filePath) {
        for (TypeDeclaration declaration : typeDeclarations) {
            Type superclassType = declaration.getSuperclassType();
            List<Type> superInterfaceTypes = declaration.superInterfaceTypes();
            List<TypeParameter> typeParameters = declaration.typeParameters();
            List<IExtendedModifier> modifiers = declaration.modifiers();
            List<EntityInfo> entityUsages = new ArrayList<>();
            populateWithSuperClassType(superclassType, entityUsages);
            populateWithSuperInterfaceTypes(superInterfaceTypes, entityUsages);
            populateWithTypeParameters(typeParameters, entityUsages);
            populateWithModifiers(modifiers, entityUsages);
            EntityInfo typeEntity = EntityUtils.generateTypeEntity(declaration.resolveBinding());
            LocationInfo locationInfo = new LocationInfo(cu, filePath, declaration);
            typeEntity.setLocation(locationInfo);
            populateDependencyInReverse(typeEntity, entityUsages, dependencies);
        }
    }

    private void populateDependencyOnEnumDeclaration(List<EnumDeclaration> enumDeclarations, Map<EntityInfo, List<EntityInfo>> dependencies, CompilationUnit cu, String filePath) {
        for (EnumDeclaration declaration : enumDeclarations) {
            List<Type> superInterfaceTypes = declaration.superInterfaceTypes();
            List<IExtendedModifier> modifiers = declaration.modifiers();
            List<EntityInfo> entityUsages = new ArrayList<>();
            populateWithSuperInterfaceTypes(superInterfaceTypes, entityUsages);
            populateWithModifiers(modifiers, entityUsages);
            EntityInfo enumEntity = EntityUtils.generateTypeEntity(declaration.resolveBinding());
            LocationInfo locationInfo = new LocationInfo(cu, filePath, declaration);
            enumEntity.setLocation(locationInfo);
            populateDependencyInReverse(enumEntity, entityUsages, dependencies);
        }
    }

    private void populateDependencyOnAnnotationTypeDeclaration(List<AnnotationTypeDeclaration> annotationTypeDeclarations, Map<EntityInfo, List<EntityInfo>> dependencies, CompilationUnit cu, String filePath) {
        for (AnnotationTypeDeclaration declaration : annotationTypeDeclarations) {
            List<IExtendedModifier> modifiers = declaration.modifiers();
            List<EntityInfo> entityUsages = new ArrayList<>();
            populateWithModifiers(modifiers, entityUsages);
            EntityInfo annotationTypeEntity = EntityUtils.generateTypeEntity(declaration.resolveBinding());
            LocationInfo locationInfo = new LocationInfo(cu, filePath, declaration);
            annotationTypeEntity.setLocation(locationInfo);
            populateDependencyInReverse(annotationTypeEntity, entityUsages, dependencies);
        }
    }

    private void populateDependencyOnRecordDeclaration(List<RecordDeclaration> recordDeclarations, Map<EntityInfo, List<EntityInfo>> dependencies, CompilationUnit cu, String filePath) {
        for (RecordDeclaration declaration : recordDeclarations) {
            List<IExtendedModifier> modifiers = declaration.modifiers();
            List<TypeParameter> typeParameters = declaration.typeParameters();
            List<Type> superInterfaceTypes = declaration.superInterfaceTypes();
            List<EntityInfo> entityUsages = new ArrayList<>();
            populateWithSuperInterfaceTypes(superInterfaceTypes, entityUsages);
            populateWithTypeParameters(typeParameters, entityUsages);
            populateWithModifiers(modifiers, entityUsages);
            EntityInfo recordEntity = EntityUtils.generateTypeEntity(declaration.resolveBinding());
            LocationInfo locationInfo = new LocationInfo(cu, filePath, declaration);
            recordEntity.setLocation(locationInfo);
            populateDependencyInReverse(recordEntity, entityUsages, dependencies);
        }
    }

    private void populateWithSuperClassType(Type superclassType, List<EntityInfo> entityUsages) {
        if (superclassType != null) {
            NodeUsageVisitor visitor = new NodeUsageVisitor();
            superclassType.accept(visitor);
            entityUsages.addAll(visitor.getEntityUsages());
        }
    }

    private void populateWithSuperInterfaceTypes(List<Type> superInterfaceTypes, List<EntityInfo> dependencies) {
        for (Type superInterfaceType : superInterfaceTypes) {
            NodeUsageVisitor visitor = new NodeUsageVisitor();
            superInterfaceType.accept(visitor);
            dependencies.addAll(visitor.getEntityUsages());
        }
    }

    private void populateWithTypeParameters(List<TypeParameter> typeParameters, List<EntityInfo> entityUsages) {
        for (TypeParameter typeParameter : typeParameters) {
            NodeUsageVisitor visitor = new NodeUsageVisitor();
            typeParameter.accept(visitor);
            entityUsages.addAll(visitor.getEntityUsages());
        }
    }

    private void populateWithModifiers(List<IExtendedModifier> modifiers, List<EntityInfo> entityUsages) {
        for (IExtendedModifier modifier : modifiers) {
            if (modifier.isAnnotation()) {
                Annotation annotation = (Annotation) modifier;
                NodeUsageVisitor visitor = new NodeUsageVisitor();
                annotation.accept(visitor);
                entityUsages.addAll(visitor.getEntityUsages());
            }
        }
    }

    private void populateDependencyInInitializers(List<Initializer> initializers, Map<EntityInfo, List<EntityInfo>> dependencies, CompilationUnit cu, String filePath) {
        for (Initializer initializer : initializers) {
            NodeUsageVisitor visitor = new NodeUsageVisitor();
            initializer.accept(visitor);
            List<EntityInfo> entityUsages = visitor.getEntityUsages();
            ITypeBinding typeBinding = ((AbstractTypeDeclaration) initializer.getParent()).resolveBinding();
            int modifiers = initializer.getModifiers();
            EntityInfo initializerEntity = EntityUtils.generateInitializerEntity(typeBinding, Flags.isStatic(modifiers) ? "static block" : "non-static block");
            LocationInfo locationInfo = new LocationInfo(cu, filePath, initializer);
            initializerEntity.setLocation(locationInfo);
            populateDependencyInReverse(initializerEntity, entityUsages, dependencies);
        }
    }

    private void populateDependencyInFieldDeclaration(List<FieldDeclaration> fieldDeclarations, Map<EntityInfo, List<EntityInfo>> dependencies, CompilationUnit cu, String filePath) {
        for (FieldDeclaration declaration : fieldDeclarations) {
            List<VariableDeclarationFragment> fragments = declaration.fragments();
            for (VariableDeclarationFragment fragment : fragments) {
                NodeUsageVisitor visitor = new NodeUsageVisitor();
                declaration.getType().accept(visitor);
                fragment.accept(visitor);
                List<EntityInfo> entityUsages = visitor.getEntityUsages();
                IVariableBinding variableBinding = fragment.resolveBinding();
                ITypeBinding declaringClass = variableBinding.getDeclaringClass();
                entityUsages.removeIf(dependency -> dependency.getType() == EntityType.FIELD &&
                        StringUtils.equals(dependency.getName(), variableBinding.getName()) &&
                        StringUtils.equals(dependency.getContainer(), declaringClass.getQualifiedName()));
                EntityInfo fieldEntity = EntityUtils.generateFieldEntity(variableBinding);
                LocationInfo locationInfo = new LocationInfo(cu, filePath, fragment);
                fieldEntity.setLocation(locationInfo);
                populateDependencyInReverse(fieldEntity, entityUsages, dependencies);
            }
        }
    }

    private void populateDependencyInMethodDeclaration(List<MethodDeclaration> methodDeclarations, Map<EntityInfo, List<EntityInfo>> dependencies, CompilationUnit cu, String filePath) {
        for (MethodDeclaration declaration : methodDeclarations) {
            NodeUsageVisitor visitor = new NodeUsageVisitor();
            declaration.accept(visitor);
            List<EntityInfo> entityUsages = visitor.getEntityUsages();
            IMethodBinding methodBinding = declaration.resolveBinding();
            ITypeBinding declaringClass = methodBinding.getDeclaringClass();
            ITypeBinding[] parameterTypes = methodBinding.getParameterTypes();
            String params = Arrays.stream(parameterTypes).map(ITypeBinding::getName).collect(Collectors.joining(","));
            entityUsages.removeIf(dependency -> dependency.getType() == EntityType.METHOD &&
                    StringUtils.equals(dependency.getName(), methodBinding.getName()) &&
                    StringUtils.equals(dependency.getParams(), params) &&
                    StringUtils.equals(dependency.getContainer(), declaringClass.getQualifiedName()));
            EntityInfo methodEntity = EntityUtils.generateMethodEntity(declaration.resolveBinding());
            LocationInfo locationInfo = new LocationInfo(cu, filePath, declaration);
            methodEntity.setLocation(locationInfo);
            populateDependencyInReverse(methodEntity, entityUsages, dependencies);
        }
    }

    private void populateDependencyInAnnotationMemberDeclaration(List<AnnotationTypeMemberDeclaration> annotationMemberDeclarations, Map<EntityInfo, List<EntityInfo>> dependencies, CompilationUnit cu, String filePath) {
        for (AnnotationTypeMemberDeclaration declaration : annotationMemberDeclarations) {
            NodeUsageVisitor visitor = new NodeUsageVisitor();
            declaration.accept(visitor);
            List<EntityInfo> entityUsages = visitor.getEntityUsages();
            EntityInfo typeMemberEntity = EntityUtils.generateMethodEntity(declaration.resolveBinding());
            LocationInfo locationInfo = new LocationInfo(cu, filePath, declaration);
            typeMemberEntity.setLocation(locationInfo);
            populateDependencyInReverse(typeMemberEntity, entityUsages, dependencies);
        }
    }

    private void populateDependencyInEnumConstant(List<EnumConstantDeclaration> enumConstantDeclarations, Map<EntityInfo, List<EntityInfo>> dependencies, CompilationUnit cu, String filePath) {
        for (EnumConstantDeclaration declaration : enumConstantDeclarations) {
            NodeUsageVisitor visitor = new NodeUsageVisitor();
            declaration.accept(visitor);
            List<EntityInfo> entityUsages = visitor.getEntityUsages();
            IVariableBinding variableBinding = declaration.resolveVariable();
            ITypeBinding declaringClass = variableBinding.getDeclaringClass();
            entityUsages.removeIf(dependency -> dependency.getType() == EntityType.ENUM_CONSTANT &&
                    StringUtils.equals(dependency.getName(), variableBinding.getName()) &&
                    StringUtils.equals(dependency.getContainer(), declaringClass.getQualifiedName()));
            EntityInfo enumConstantEntity = EntityUtils.generateFieldEntity(declaration.resolveVariable());
            LocationInfo locationInfo = new LocationInfo(cu, filePath, declaration);
            enumConstantEntity.setLocation(locationInfo);
            populateDependencyInReverse(enumConstantEntity, entityUsages, dependencies);
        }
    }

    private void populateDependencyInReverse(EntityInfo entity, List<EntityInfo> entityUsages, Map<EntityInfo, List<EntityInfo>> dependencies) {
        for (EntityInfo dependency : entityUsages) {
            if (dependencies.containsKey(dependency))
                dependencies.get(dependency).add(entity);
            else
                dependencies.put(dependency, new ArrayList<>(Collections.singletonList(entity)));
        }
    }

    private void populateCurrentDependencies(MatchPair matchPair, String projectPath, Set<String> modifiedFiles,
                                             Map<String, String> renamedFiles, Set<String> addedFiles) {
        ProjectParser parser = new ProjectParser(projectPath);
        Map<EntityInfo, DeclarationNodeTree> entities = new HashMap<>();
        List<String> changedJavaFiles = new ArrayList<>();
        changedJavaFiles.addAll(modifiedFiles);
        changedJavaFiles.addAll(addedFiles);
        changedJavaFiles.addAll(renamedFiles.values());
        Map<EntityInfo, List<EntityInfo>> dependencies = new HashMap<>();
        parser.buildEntityDependencies(changedJavaFiles);
        populateEntityDependencies(parser, dependencies);
        for (DeclarationNodeTree dnt : matchPair.getMatchedEntitiesRight())
            entities.put(dnt.getEntity(), dnt);
        for (DeclarationNodeTree dnt : matchPair.getCandidateEntitiesRight())
            entities.put(dnt.getEntity(), dnt);
        for (DeclarationNodeTree dnt : matchPair.getAddedEntities())
            entities.put(dnt.getEntity(), dnt);
        for (EntityInfo entity : entities.keySet()) {
            if (dependencies.containsKey(entity))
                entities.get(entity).addDependencies(dependencies.get(entity));
        }
    }

    private void populateBeforeDependencies(MatchPair matchPair, String projectPath, Set<String> modifiedFiles,
                                            Map<String, String> renamedFiles, Set<String> deletedFiles) {
        ProjectParser parser = new ProjectParser(projectPath);
        List<String> changedJavaFiles = new ArrayList<>();
        Map<EntityInfo, DeclarationNodeTree> entities = new HashMap<>();
        changedJavaFiles.addAll(modifiedFiles);
        changedJavaFiles.addAll(deletedFiles);
        changedJavaFiles.addAll(renamedFiles.keySet());
        Map<EntityInfo, List<EntityInfo>> dependencies = new HashMap<>();
        parser.buildEntityDependencies(changedJavaFiles);
        populateEntityDependencies(parser, dependencies);
        for (DeclarationNodeTree dnt : matchPair.getMatchedEntitiesLeft())
            entities.put(dnt.getEntity(), dnt);
        for (DeclarationNodeTree dnt : matchPair.getCandidateEntitiesLeft())
            entities.put(dnt.getEntity(), dnt);
        for (DeclarationNodeTree dnt : matchPair.getDeletedEntities())
            entities.put(dnt.getEntity(), dnt);
        for (EntityInfo entity : entities.keySet()) {
            if (dependencies.containsKey(entity))
                entities.get(entity).addDependencies(dependencies.get(entity));
        }
    }

    private void fineMatching(MatchPair matchPair) {
        for (int i = 0; i < 10; i++) {
            Set<Pair<DeclarationNodeTree, DeclarationNodeTree>> temp = new HashSet<>();
            Set<DeclarationNodeTree> beforeEntities = new HashSet<>();
            Set<DeclarationNodeTree> currentEntities = new HashSet<>();
            List<EntityPair> entityPairs = new ArrayList<>();
            beforeEntities.addAll(matchPair.getCandidateEntitiesLeft());
            beforeEntities.addAll(matchPair.getDeletedEntities());
            currentEntities.addAll(matchPair.getCandidateEntitiesRight());
            currentEntities.addAll(matchPair.getAddedEntities());
            for (DeclarationNodeTree dntBefore : beforeEntities) {
                for (DeclarationNodeTree dntCurrent : currentEntities) {
                    if (dntBefore.getType() == dntCurrent.getType() ||
                            ((dntBefore.getType() == EntityType.CLASS || dntBefore.getType() == EntityType.INTERFACE || dntBefore.getType() == EntityType.ENUM) &&
                                    (dntCurrent.getType() == EntityType.CLASS || dntCurrent.getType() == EntityType.INTERFACE || dntCurrent.getType() == EntityType.ENUM))) {
                        double dice = DiceFunction.calculateSimilarity(matchPair, dntBefore, dntCurrent);
                        if (dice < DiceFunction.minDice)
                            continue;
                        EntityPair entityPair = new EntityPair(dntBefore, dntCurrent);
                        entityPairs.add(entityPair);
                        entityPair.setDice(dice);
                    }
                }
            }
            Collections.sort(entityPairs);
            Set<DeclarationNodeTree> existBefore = new HashSet<>();
            Set<DeclarationNodeTree> existCurrent = new HashSet<>();
            for (EntityPair entityPair : entityPairs) {
                DeclarationNodeTree node1 = entityPair.getLeft();
                DeclarationNodeTree node2 = entityPair.getRight();
                if (existBefore.contains(node1) || existCurrent.contains(node2))
                    continue;
                existBefore.add(node1);
                existCurrent.add(node2);
                temp.add(Pair.of(node1, node2));
            }
            if (matchPair.getCandidateEntities().size() == temp.size() && matchPair.getCandidateEntities().equals(temp)) {
//                System.out.println("At the " + (i + 1) + "th iteration, the contents of candidate set do not change.");
                break;
            }
            matchPair.setCandidateEntities(temp);
            beforeEntities.removeAll(existBefore);
            matchPair.updateDeletedEntities(beforeEntities);
            currentEntities.removeAll(existCurrent);
            matchPair.updateAddedEntities(currentEntities);
        }
        matchPair.getMatchedEntities().addAll(matchPair.getCandidateEntities());
        matchPair.getCandidateEntities().clear();
    }

    private void additionalMatchByName(MatchPair matchPair) {
        Set<DeclarationNodeTree> deletionBefore = new HashSet<>();
        Set<DeclarationNodeTree> deletionCurrent = new HashSet<>();
        List<EntityPair> entityPairs = new ArrayList<>();
        for (DeclarationNodeTree dntBefore : matchPair.getDeletedEntities()) {
            for (DeclarationNodeTree dntCurrent : matchPair.getAddedEntities()) {
                if (dntBefore.equals(dntCurrent)) {
                    double dice = 0;
                    if (dntBefore instanceof InternalNode && dntCurrent instanceof InternalNode) {
                        dice = DiceFunction.calculateDice(matchPair, (InternalNode) dntBefore, (InternalNode) dntCurrent);
                    } else if (dntBefore instanceof LeafNode && dntCurrent instanceof LeafNode) {
                        dice = DiceFunction.calculateDice((LeafNode) dntBefore, (LeafNode) dntCurrent);
                    }
                    EntityPair entityPair = new EntityPair(dntBefore, dntCurrent);
                    entityPairs.add(entityPair);
                    entityPair.setDice(dice);
                } else if (dntBefore.getType() == EntityType.ENUM && dntCurrent.getType() == EntityType.ENUM &&
                        dntBefore.getName().equals(dntCurrent.getName())) {
                    List<DeclarationNodeTree> children1 = dntBefore.getChildren();
                    List<DeclarationNodeTree> children2 = dntCurrent.getChildren();
                    List<DeclarationNodeTree> methods1 = children1.stream().filter(dnt -> dnt.getType() != EntityType.ENUM_CONSTANT).collect(Collectors.toList());
                    List<DeclarationNodeTree> methods2 = children2.stream().filter(dnt -> dnt.getType() != EntityType.ENUM_CONSTANT).collect(Collectors.toList());
                    List<DeclarationNodeTree> constants1 = children1.stream().filter(dnt -> dnt.getType() == EntityType.ENUM_CONSTANT).collect(Collectors.toList());
                    List<DeclarationNodeTree> constants2 = children2.stream().filter(dnt -> dnt.getType() == EntityType.ENUM_CONSTANT).collect(Collectors.toList());
                    if (methods1.size() == methods2.size()) {
                        int intersection = 0;
                        int constants = 0;
                        for (DeclarationNodeTree leafBefore : methods1) {
                            for (DeclarationNodeTree leafCurrent : methods2) {
                                if (matchPair.getMatchedEntities().contains(Pair.of(leafBefore, leafCurrent)) ||
                                        matchPair.getCandidateEntities().contains(Pair.of(leafBefore, leafCurrent)))
                                    intersection++;
                            }
                        }
                        for (DeclarationNodeTree leafBefore : constants1) {
                            for (DeclarationNodeTree leafCurrent : constants2) {
                                if (matchPair.getMatchedEntities().contains(Pair.of(leafBefore, leafCurrent)) ||
                                        matchPair.getCandidateEntities().contains(Pair.of(leafBefore, leafCurrent)))
                                    constants++;
                            }
                        }
                        if (methods1.size() > 0 && methods1.size() == intersection && constants > 0) {
                            EntityPair entityPair = new EntityPair(dntBefore, dntCurrent);
                            entityPairs.add(entityPair);
                            entityPair.setDice(constants);
                        }
                    }
                }
            }
        }
        selectByDice(matchPair, deletionBefore, deletionCurrent, entityPairs);
    }

    private void additionalMatchByDice(MatchPair matchPair) {
        Set<DeclarationNodeTree> deletionBefore = new HashSet<>();
        Set<DeclarationNodeTree> deletionCurrent = new HashSet<>();
        List<EntityPair> entityPairs = new ArrayList<>();
        for (DeclarationNodeTree dntBefore : matchPair.getDeletedEntities()) {
            for (DeclarationNodeTree dntCurrent : matchPair.getAddedEntities()) {
                if (dntBefore.getType() == dntCurrent.getType()) {
                    double dice = 0;
                    if (dntBefore instanceof InternalNode && dntCurrent instanceof InternalNode) {
                        if (dntBefore.getType() == dntCurrent.getType() &&
                                !dntBefore.hasChildren() && !dntCurrent.hasChildren() &&
                                dntBefore.getDependencies().size() == 0 && dntCurrent.getDependencies().size() == 0 &&
                                dntBefore.getHeight() == 1 && dntCurrent.getHeight() == 1 &&
                                StringUtils.equals(dntBefore.getNamespace(), dntCurrent.getNamespace()) &&
                                (((AbstractTypeDeclaration) dntBefore.getDeclaration()).getModifiers() & Modifier.PUBLIC) != 0 &&
                                (((AbstractTypeDeclaration) dntCurrent.getDeclaration()).getModifiers() & Modifier.PUBLIC) != 0) {
                            EntityPair entityPair = new EntityPair(dntBefore, dntCurrent);
                            entityPairs.add(entityPair);
                            entityPair.setDice(1.0);
                            continue;
                        }
                        dice = DiceFunction.calculateDice(matchPair, (InternalNode) dntBefore, (InternalNode) dntCurrent);
                    } else if (dntBefore instanceof LeafNode && dntCurrent instanceof LeafNode) {
                        dice = DiceFunction.calculateDice((LeafNode) dntBefore, (LeafNode) dntCurrent);
                    }
                    if (dice <= 0.8) continue;
                    EntityPair entityPair = new EntityPair(dntBefore, dntCurrent);
                    entityPairs.add(entityPair);
                    entityPair.setDice(dice);
                }
            }
        }
        selectByDice(matchPair, deletionBefore, deletionCurrent, entityPairs);
    }

    private void selectByDice(MatchPair matchPair, Set<DeclarationNodeTree> deletionBefore, Set<DeclarationNodeTree> deletionCurrent, List<EntityPair> entityPairs) {
        Collections.sort(entityPairs);
        Set<DeclarationNodeTree> existBefore = new HashSet<>();
        Set<DeclarationNodeTree> existCurrent = new HashSet<>();
        for (EntityPair entityPair : entityPairs) {
            DeclarationNodeTree node1 = entityPair.getLeft();
            DeclarationNodeTree node2 = entityPair.getRight();
            if (existBefore.contains(node1) || existCurrent.contains(node2))
                continue;
            existBefore.add(node1);
            existCurrent.add(node2);
            matchPair.addMatchedEntity(node1, node2);
            deletionBefore.add(node1);
            deletionCurrent.add(node2);
        }
        matchPair.getDeletedEntities().removeAll(deletionBefore);
        matchPair.getAddedEntities().removeAll(deletionCurrent);
    }

    private void filter(MatchPair matchPair) {
        Set<Pair<DeclarationNodeTree, DeclarationNodeTree>> matchedEntities = matchPair.getMatchedEntities();
        Set<Pair<DeclarationNodeTree, DeclarationNodeTree>> filteredEntities = new HashSet<>();
        for (Pair<DeclarationNodeTree, DeclarationNodeTree> pair : matchedEntities) {
            DeclarationNodeTree dntBefore = pair.getLeft();
            DeclarationNodeTree dntCurrent = pair.getRight();
            if (dntBefore.getDeclaration().toString().equals(dntCurrent.getDeclaration().toString()) &&
                    StringUtils.equals(dntBefore.getNamespace(), dntCurrent.getNamespace())) {
                filteredEntities.add(pair);
            }
        }
        matchedEntities.removeAll(filteredEntities);
        matchPair.addUnchangedEntities(filteredEntities);
    }
}
