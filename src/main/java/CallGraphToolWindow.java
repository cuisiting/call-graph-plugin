import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.module.ModuleManager;
import com.intellij.openapi.module.impl.scopes.ModulesScope;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.ProgressIndicatorProvider;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.ProjectManager;
import com.intellij.openapi.roots.ContentIterator;
import com.intellij.openapi.roots.ModuleRootManager;
import com.intellij.openapi.roots.ProjectRootManager;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VfsUtilCore;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.wm.WindowManager;
import com.intellij.psi.*;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.search.SearchScope;
import com.intellij.psi.search.searches.ReferencesSearch;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.psi.util.PsiUtilCore;
import guru.nidi.graphviz.attribute.RankDir;
import guru.nidi.graphviz.engine.Format;
import guru.nidi.graphviz.engine.Graphviz;
import guru.nidi.graphviz.model.MutableNode;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.awt.*;
import java.util.List;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static guru.nidi.graphviz.model.Factory.mutGraph;
import static guru.nidi.graphviz.model.Factory.mutNode;

@SuppressWarnings("WeakerAccess")
public class CallGraphToolWindow {
    private JButton runButton;
    private JPanel callGraphToolWindowContent;
    private JPanel canvasPanel;
    private JRadioButton projectScopeButton;
    private JRadioButton moduleScopeButton;
    private JRadioButton directoryScopeButton;
    private JTextField directoryScopeTextField;
    private JComboBox<String> moduleScopeComboBox;
    private JTabbedPane mainTabbedPanel;
    private JCheckBox includeTestFilesCheckBox;
    private JLabel buildOptionLabel;
    private JLabel statusLabel;

    private ProgressIndicator progressIndicator;
    private final float xGridRatio = 1.0f;
    private final float yGridRatio = 2.0f;
    private enum BuildOption {
        WHOLE_PROJECT_WITH_TEST("Whole project (test files included)"),
        WHOLE_PROJECT_WITHOUT_TEST("Whole project (test files excluded)"),
        MODULE("Module"),
        DIRECTORY("Directory");

        private final String label;

        BuildOption(@NotNull String label) {
            this.label = label;
        }

        @NotNull
        public String getLabel() {
            return this.label;
        }
    }

    public CallGraphToolWindow() {
        // click handlers for buttons
        this.runButton.addActionListener(e -> run());
        this.projectScopeButton.addActionListener(e -> projectScopeButtonHandler());
        this.moduleScopeButton.addActionListener(e -> moduleScopeButtonHandler());
        this.directoryScopeButton.addActionListener(e -> directoryScopeButtonHandler());
    }

    void disableAllSecondaryOptions() {
        this.includeTestFilesCheckBox.setEnabled(false);
        this.moduleScopeComboBox.setEnabled(false);
        this.directoryScopeTextField.setEnabled(false);
    }

    void projectScopeButtonHandler() {
        disableAllSecondaryOptions();
        this.includeTestFilesCheckBox.setEnabled(true);
    }

    void moduleScopeButtonHandler() {
        Project project = getActiveProject();
        if (project != null) {
            // set up modules drop-down
            this.moduleScopeComboBox.removeAllItems();
            getActiveModules(project)
                    .forEach(module -> this.moduleScopeComboBox.addItem(module.getName()));
            disableAllSecondaryOptions();
            this.moduleScopeComboBox.setEnabled(true);
        }
    }

    void directoryScopeButtonHandler() {
        Project project = getActiveProject();
        if (project != null) {
            // set up directory option text field
            this.directoryScopeTextField.setText(project.getBasePath());
            disableAllSecondaryOptions();
            this.directoryScopeTextField.setEnabled(true);
        }
    }

    public void run() {
        Project project = getActiveProject();
        if (project == null) {
            return;
        }
        // cancel existing progress if any
        if (this.progressIndicator != null) {
            this.progressIndicator.cancel();
        }
        ProgressManager.getInstance().run(
                new Task.Backgroundable(project, "Call Graph") {
                    public void run(@NotNull ProgressIndicator progressIndicator) {
                        ApplicationManager.getApplication()
                                .runReadAction(() -> main(project));
                    }
                }
        );
    }

    public void main(@NotNull Project project) {
        setUpLoadingStartUI();
        this.progressIndicator = ProgressIndicatorProvider.getGlobalProgressIndicator();
        System.out.println("--- getting source code files ---");
        Set<PsiFile> sourceCodeFiles = getSourceCodeFiles(project);
        System.out.println(String.format("found %d files", sourceCodeFiles.size()));
        System.out.println("--- getting method references ---");
        Map<PsiMethod, Set<PsiMethod>> methodCallersMap = getMethodCallersMap(project, sourceCodeFiles);
        System.out.println(String.format("found %d methods and %d callers in total", methodCallersMap.size(),
                methodCallersMap.values().stream().map(Set::size).mapToInt(Integer::intValue).sum()));
        System.out.println("--- building graph ---");
        Graph graph = buildGraph(methodCallersMap);
        System.out.println("--- getting layout from GraphViz ---");
        layoutByGraphViz(graph);
        System.out.println("--- rendering graph ---");
        Canvas canvas = renderGraphOnCanvas(graph, project);
        System.out.println("--- attaching event listeners ---");
        attachEventListeners(canvas);
        setUpLoadingEndUI();
    }

    private void setUpLoadingStartUI() {
        focusGraphTab();
        BuildOption buildOption = getSelectedBuildOption();
        switch (buildOption) {
            case WHOLE_PROJECT_WITH_TEST:
                // fall through
            case WHOLE_PROJECT_WITHOUT_TEST:
                setBuiltByLabel(buildOption.getLabel());
                break;
            case MODULE:
                String moduleName = (String) this.moduleScopeComboBox.getSelectedItem();
                setBuiltByLabel(String.format("%s [%s]", buildOption.getLabel(), moduleName));
                break;
            case DIRECTORY:
                String path = this.directoryScopeTextField.getText();
                setBuiltByLabel(String.format("%s [%s]", buildOption.getLabel(), path));
                break;
            default:
                break;
        }
        setStatusLabel("building graph...");
        this.canvasPanel.removeAll();
    }

    private void setUpLoadingEndUI() {
        setStatusLabel("done");
    }

    @Nullable
    private Project getActiveProject() {
        Project[] projects = ProjectManager.getInstance().getOpenProjects();
        return Stream.of(projects)
                .filter(project -> {
                    Window window = WindowManager.getInstance().suggestParentWindow(project);
                    return window != null && window.isActive();
                })
                .findFirst()
                .orElse(null);
    }

    @NotNull
    private List<Module> getActiveModules(@NotNull Project project) {
        return Arrays.asList(ModuleManager.getInstance(project).getModules());
    }

    @NotNull
    private Set<PsiFile> getSourceCodeFiles(@NotNull Project project) {
        return getSourceCodeRoots(project)
                .stream()
                .flatMap(contentSourceRoot -> {
                    List<VirtualFile> childrenVirtualFiles = new ArrayList<>();
                    ContentIterator contentIterator = child -> {
                        if (child.isValid() && !child.isDirectory()) {
                            String extension = Optional.ofNullable(child.getExtension()).orElse("");
                            if (extension.equals("java")) {
                                childrenVirtualFiles.add(child);
                            }
                        }
                        return true;
                    };
                    VfsUtilCore.iterateChildrenRecursively(contentSourceRoot, null, contentIterator);
                    return childrenVirtualFiles.stream()
                            .map(file -> PsiManager.getInstance(project).findFile(file));
                })
                .collect(Collectors.toSet());
    }

    @NotNull
    private Set<VirtualFile> getSourceCodeRoots(@NotNull Project project) {
        switch (getSelectedBuildOption()) {
            case WHOLE_PROJECT_WITH_TEST:
                VirtualFile[] contentRoots = ProjectRootManager.getInstance(project).getContentRoots();
                return new HashSet<>(Arrays.asList(contentRoots));
            case WHOLE_PROJECT_WITHOUT_TEST:
                return getActiveModules(project)
                        .stream()
                        .flatMap(module -> Stream.of(ModuleRootManager.getInstance(module).getSourceRoots(false)))
                        .collect(Collectors.toSet());
            case MODULE:
                return getSelectedModules(project)
                        .stream()
                        .flatMap(module -> Stream.of(ModuleRootManager.getInstance(module).getSourceRoots()))
                        .collect(Collectors.toSet());
            case DIRECTORY:
                String path = this.directoryScopeTextField.getText();
                if (!path.isEmpty()) {
                    VirtualFile root = LocalFileSystem.getInstance().findFileByPath(path);
                    if (root != null) {
                        return Collections.singleton(root);
                    }
                }
                return Collections.emptySet();
            default:
                break;
        }
        return Collections.emptySet();
    }

    @NotNull
    private Map<PsiMethod, Set<PsiMethod>> getMethodCallersMap(
            @NotNull Project project,
            @NotNull Set<PsiFile> sourceCodeFiles) {
        Set<PsiMethod> allMethods = sourceCodeFiles.stream()
                .flatMap(psiFile -> Stream.of(((PsiJavaFile)psiFile).getClasses())) // get all classes
                .flatMap(psiClass -> Stream.of(psiClass.getMethods())) // get all methods
                .collect(Collectors.toSet());
        return allMethods.stream()
                .collect(Collectors.toMap(
                        method -> method,
                        method -> {
                            SearchScope searchScope = getSearchScope(project, method);
                            long start = new Date().getTime();
                            Collection<PsiReference> references =
                                    ReferencesSearch.search(method, searchScope).findAll();
                            long now = new Date().getTime();
                            System.out.printf("%d milliseconds for method %s\n", now - start, method.getName());
                            return references.stream()
                                    .map(reference -> getContainingKnownMethod(reference.getElement(), allMethods))
                                    .filter(Objects::nonNull)
                                    .collect(Collectors.toSet());
                        }
                ));
    }

    private Graph buildGraph(@NotNull Map<PsiMethod, Set<PsiMethod>> methodCallersMap) {
        Graph graph = new Graph();
        methodCallersMap.forEach((callee, callers) -> {
            graph.addNode(callee);
            callers.forEach(caller -> {
                graph.addNode(caller);
                graph.addEdge(caller, callee);
            });
        });
        return graph;
    }

    @NotNull
    private Canvas renderGraphOnCanvas(@NotNull Graph graph, @NotNull Project project) {
        Canvas canvas = new Canvas()
                .setGraph(graph)
                .setCanvasPanel(this.canvasPanel)
                .setProject(project);
        this.canvasPanel.add(canvas);
        this.canvasPanel.updateUI();
        return canvas;
    }

    private void attachEventListeners(@NotNull Canvas canvas) {
        MouseEventHandler mouseEventHandler = new MouseEventHandler();
        mouseEventHandler.init(canvas);
        canvas.addMouseListener(mouseEventHandler);
        canvas.addMouseMotionListener(mouseEventHandler);
        canvas.addMouseWheelListener(mouseEventHandler);
    }

    @Nullable
    private PsiMethod getContainingKnownMethod(@NotNull PsiElement psiElement, @NotNull Set<PsiMethod> knownMethods) {
        PsiMethod parent = PsiTreeUtil.getParentOfType(psiElement, PsiMethod.class);
        if (parent == null) {
            return null;
        }
        return knownMethods.contains(parent) ? parent : getContainingKnownMethod(parent, knownMethods);
    }

    private void layoutByGraphViz(@NotNull Graph graph) {
        guru.nidi.graphviz.model.MutableGraph gvGraph = mutGraph("test")
                .setDirected(true)
                .graphAttrs()
                .add(RankDir.LEFT_TO_RIGHT);

        Collection<Node> sortedNodes = getSortedNodes(graph.getNodes());
        sortedNodes.forEach(node -> {
            MutableNode gvNode = mutNode(node.getId());
            Collection<Node> neighbors = node.getLeavingEdges()
                    .values()
                    .stream()
                    .map(Edge::getTargetNode)
                    .collect(Collectors.toSet());
            Collection<Node> sortedNeighbors = getSortedNodes(neighbors);
            sortedNeighbors.forEach(neighborNode -> gvNode.addLink(neighborNode.getId()));
            gvGraph.add(gvNode);
        });
        String layoutBlueprint = Graphviz.fromGraph(gvGraph).render(Format.PLAIN).toString();

        // parse the GraphViz layout as a mapping from "node name" to "x-y coordinate (percent of full graph size)"
        // GraphViz doc: https://graphviz.gitlab.io/_pages/doc/info/output.html#d:plain
        List<String> layoutLines = Arrays.asList(layoutBlueprint.split("\n"));
        String graphSizeLine = layoutLines.stream()
                .filter(line -> line.startsWith("graph"))
                .findFirst()
                .orElse("");
        String[] graphSizeParts = graphSizeLine.split(" ");
        float graphWidth = Float.parseFloat(graphSizeParts[2]);
        float graphHeight = Float.parseFloat(graphSizeParts[3]);
        layoutLines.stream()
                .filter(line -> line.startsWith("node"))
                .map(line -> line.split(" "))
                .forEach(parts -> {
                    String nodeId = parts[1];
                    float x = this.xGridRatio * Float.parseFloat(parts[2]) / graphWidth;
                    float y = this.yGridRatio * Float.parseFloat(parts[3]) / graphHeight;
                    graph.getNode(nodeId).setCoordinate(x, y);
                });
    }

    @NotNull
    private Collection<Node> getSortedNodes(@NotNull Collection<Node> nodes) {
        List<Node> sortedNodes = new ArrayList<>(nodes);
        sortedNodes.sort(Comparator.comparing(node -> node.getMethod().getName()));
        return sortedNodes;
    }

    @NotNull
    public JPanel getContent() {
        return this.callGraphToolWindowContent;
    }

    @NotNull
    private Set<Module> getSelectedModules(@NotNull Project project) {
        String selectedModuleName = (String) this.moduleScopeComboBox.getSelectedItem();
        return getActiveModules(project)
                .stream()
                .filter(module -> module.getName().equals(selectedModuleName))
                .collect(Collectors.toSet());
    }

    @NotNull
    private SearchScope getSearchScope(@NotNull Project project, @NotNull PsiMethod method) {
        switch (getSelectedBuildOption()) {
            case WHOLE_PROJECT_WITH_TEST:
                return GlobalSearchScope.allScope(PsiUtilCore.getProjectInReadAction(method));
            case WHOLE_PROJECT_WITHOUT_TEST:
                GlobalSearchScope[] modulesScope = getActiveModules(project)
                        .stream()
                        .map(module -> module.getModuleScope(false))
                        .toArray(GlobalSearchScope[]::new);
                return GlobalSearchScope.union(modulesScope);
            case MODULE:
                Set<Module> selectedModules = getSelectedModules(project);
                return new ModulesScope(selectedModules, project);
            case DIRECTORY:
                System.out.println("(getSearchScope) Directory scope not implemented");
                break;
            default:
                break;
        }
        return GlobalSearchScope.allScope(PsiUtilCore.getProjectInReadAction(method));
    }

    private void focusGraphTab() {
        this.mainTabbedPanel.setSelectedIndex(1);
    }

    private void setBuiltByLabel(@NotNull String text) {
        this.buildOptionLabel.setText(String.format("Built by: %s", text));
    }

    private void setStatusLabel(@NotNull String text) {
        this.statusLabel.setText(String.format("Status: %s", text));
    }

    @NotNull
    private BuildOption getSelectedBuildOption() {
        if (this.projectScopeButton.isSelected()) {
            if (this.includeTestFilesCheckBox.isSelected()) {
                return BuildOption.WHOLE_PROJECT_WITH_TEST;
            } else {
                return BuildOption.WHOLE_PROJECT_WITHOUT_TEST;
            }
        } else if (this.moduleScopeButton.isSelected()) {
            return BuildOption.MODULE;
        } else if (this.directoryScopeButton.isSelected()) {
            return BuildOption.DIRECTORY;
        }
        return BuildOption.WHOLE_PROJECT_WITH_TEST;
    }
}