package org.egothor.methodatlas.gui.panel;

import org.egothor.methodatlas.gui.model.AnalysisModel;
import org.egothor.methodatlas.gui.model.MethodEntry;
import org.egothor.methodatlas.gui.model.MethodEntry.TagStatus;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.tree.*;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.beans.PropertyChangeEvent;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Left panel containing the results tree.
 *
 * <p>The tree groups discovered methods under their class nodes. Each method
 * node shows a colour-coded status indicator:</p>
 * <ul>
 *   <li>orange {@code !} — AI suggests tags not yet in the source</li>
 *   <li>green {@code ✓} — source tags satisfy the AI suggestion</li>
 *   <li>blue {@code -} — AI says not security-relevant</li>
 *   <li>grey {@code ?} — no AI data</li>
 * </ul>
 */
@SuppressWarnings("PMD.NonSerializableClass")
public final class ScanPanel extends JPanel {

    @java.io.Serial
    private static final long serialVersionUID = 1L;

    /** Mouse click count that constitutes a double-click. */
    private static final int DOUBLE_CLICK_COUNT = 2;

    // ── Fields ────────────────────────────────────────────────────────────

    private final DefaultMutableTreeNode treeRoot = new DefaultMutableTreeNode("Results");
    private final DefaultTreeModel treeModel = new DefaultTreeModel(treeRoot);
    private final JTree tree = new JTree(treeModel);

    /** fqcn → class tree node for fast lookup */
    private final Map<String, DefaultMutableTreeNode> classNodes = new HashMap<>();

    private final AnalysisModel model;

    // ── Tree node user-objects ────────────────────────────────────────────

    /** Wrapper stored in class-level tree nodes. */
    public record ClassNode(String fqcn) {
        /** Short class name for display. */
        public String simpleName() {
            int dot = fqcn.lastIndexOf('.');
            return dot >= 0 ? fqcn.substring(dot + 1) : fqcn;
        }
    }

    /**
     * @param model model to observe and interact with
     */
    public ScanPanel(AnalysisModel model) {
        super(new BorderLayout());
        this.model = model;

        tree.setRootVisible(false);
        tree.setShowsRootHandles(true);
        tree.setCellRenderer(new MethodTreeCellRenderer());
        tree.getSelectionModel().setSelectionMode(TreeSelectionModel.SINGLE_TREE_SELECTION);
        tree.setBorder(new EmptyBorder(4, 0, 4, 0));

        tree.addTreeSelectionListener(e -> {
            DefaultMutableTreeNode selected =
                    (DefaultMutableTreeNode) tree.getLastSelectedPathComponent();
            if (selected != null && selected.getUserObject() instanceof MethodEntry entry) {
                model.setSelectedEntry(entry);
            } else {
                model.setSelectedEntry(null);
            }
        });

        // Double-click on a class node expands/collapses; on a method node opens file
        tree.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() != DOUBLE_CLICK_COUNT) { return; }
                TreePath path = tree.getPathForLocation(e.getX(), e.getY());
                if (path == null) { return; }
                DefaultMutableTreeNode node =
                        (DefaultMutableTreeNode) path.getLastPathComponent();
                if (!(node.getUserObject() instanceof ClassNode)) { return; }
                if (tree.isExpanded(path)) {
                    tree.collapsePath(path);
                } else {
                    tree.expandPath(path);
                }
            }
        });

        JScrollPane scroll = new JScrollPane(tree);
        scroll.setBorder(BorderFactory.createEmptyBorder());
        add(scroll, BorderLayout.CENTER);

        model.addPropertyChangeListener(this::onModelChange);
    }

    // ── Model observer ────────────────────────────────────────────────────

    private void onModelChange(PropertyChangeEvent evt) {
        switch (evt.getPropertyName()) {
            case "cleared" -> clearTree();
            case "entries" -> {
                if (evt.getNewValue() instanceof MethodEntry entry) {
                    insertOrUpdate(entry);
                }
            }
            case "status" -> {
                if (evt.getNewValue() == AnalysisModel.Status.DONE
                        && model.getSelectedEntry() == null) {
                    selectFirstPendingOrFirst();
                }
            }
            default -> { /* ignore */ }
        }
    }

    /**
     * Selects the first {@code NEEDS_REVIEW} method node in the tree, or the
     * very first method node when none require review.  Called automatically
     * when analysis completes and nothing is selected, so the user immediately
     * sees AI results without having to click the tree.
     */
    private void selectFirstPendingOrFirst() {
        DefaultMutableTreeNode firstMethod = null;
        for (int i = 0; i < treeRoot.getChildCount(); i++) {
            DefaultMutableTreeNode classNode = (DefaultMutableTreeNode) treeRoot.getChildAt(i);
            for (int j = 0; j < classNode.getChildCount(); j++) {
                DefaultMutableTreeNode child = (DefaultMutableTreeNode) classNode.getChildAt(j);
                if (!(child.getUserObject() instanceof MethodEntry e)) { continue; }
                if (firstMethod == null) { firstMethod = child; }
                if (e.tagStatus() == TagStatus.NEEDS_REVIEW) {
                    selectNode(child);
                    return;
                }
            }
        }
        if (firstMethod != null) { selectNode(firstMethod); }
    }

    private void selectNode(DefaultMutableTreeNode node) {
        TreePath path = new TreePath(node.getPath());
        tree.setSelectionPath(path);
        tree.scrollPathToVisible(path);
    }

    private void clearTree() {
        treeRoot.removeAllChildren();
        classNodes.clear();
        treeModel.reload();
    }

    private void insertOrUpdate(MethodEntry entry) {
        String fqcn = entry.discovered().fqcn();
        DefaultMutableTreeNode classNode = classNodes.computeIfAbsent(fqcn, k -> {
            DefaultMutableTreeNode node = new DefaultMutableTreeNode(new ClassNode(fqcn));
            treeRoot.add(node);
            treeModel.nodesWereInserted(treeRoot, new int[]{treeRoot.getIndex(node)});
            return node;
        });

        // Check if an existing method node should be updated
        for (int i = 0; i < classNode.getChildCount(); i++) {
            DefaultMutableTreeNode child = (DefaultMutableTreeNode) classNode.getChildAt(i);
            if (child.getUserObject() instanceof MethodEntry existing
                    && existing.discovered().method().equals(entry.discovered().method())) {
                // Update in-place (suggestion arrived)
                child.setUserObject(entry);
                treeModel.nodeChanged(child);
                return;
            }
        }

        // New entry
        DefaultMutableTreeNode methodNode = new DefaultMutableTreeNode(entry);
        classNode.add(methodNode);
        treeModel.nodesWereInserted(classNode, new int[]{classNode.getIndex(methodNode)});
        tree.expandPath(new TreePath(classNode.getPath()));

        // Refresh class node label (child count changed)
        treeModel.nodeChanged(classNode);
    }

    // ── Cell renderer ─────────────────────────────────────────────────────

    /**
     * Custom tree-cell renderer that colour-codes method nodes by their
     * {@link MethodEntry.TagStatus} and shows a badge on class nodes when
     * any child methods require review.
     */
    private static final class MethodTreeCellRenderer extends DefaultTreeCellRenderer {

        @java.io.Serial
        private static final long serialVersionUID = 1L;

        @Override
        public Component getTreeCellRendererComponent(JTree tree, Object value,
                boolean selected, boolean expanded, boolean leaf, int row, boolean hasFocus) {

            super.getTreeCellRendererComponent(tree, value, selected, expanded, leaf, row, hasFocus);
            setIcon(null);

            if (!(value instanceof DefaultMutableTreeNode node)) { return this; }

            switch (node.getUserObject()) {
                case ClassNode cn -> {
                    int childCount = node.getChildCount();
                    long needsReview = countNeedsReview(node);
                    String badge = needsReview > 0 ? "  ⚠ " + needsReview : "";
                    setText("<html><b>" + escHtml(cn.simpleName()) + "</b>"
                            + " <font color=gray><small>(" + childCount + ")</small></font>"
                            + (needsReview > 0
                            ? " <font color='#E08000'><small>" + badge + "</small></font>"
                            : "") + "</html>");
                    setToolTipText(cn.fqcn());
                }
                case MethodEntry entry -> {
                    TagStatus status = entry.tagStatus();
                    String indicator = switch (status) {
                        case PENDING_SAVE -> "<font color='#E65100'>✎</font>";
                        case NEEDS_REVIEW -> "<font color='#E08000'>⚠</font>";
                        case OK -> "<font color='#4CAF50'>✓</font>";
                        case NOT_SECURITY -> "<font color='#2196F3'>–</font>";
                        case NO_AI -> "<font color='gray'>○</font>";
                    };
                    List<String> displayTags = entry.hasPendingChanges()
                            ? entry.getPendingTags()
                            : entry.discovered().tags();
                    String tags = displayTags.isEmpty() ? ""
                            : " <font color='gray'>[" + String.join(", ", displayTags) + "]</font>";
                    setText("<html>" + indicator + " " + escHtml(entry.discovered().method())
                            + tags + "</html>");
                    setToolTipText(buildTooltip(entry));
                }
                default -> { /* use default rendering */ }
            }
            return this;
        }

        private static long countNeedsReview(DefaultMutableTreeNode classNode) {
            long count = 0;
            for (int i = 0; i < classNode.getChildCount(); i++) {
                if (((DefaultMutableTreeNode) classNode.getChildAt(i))
                        .getUserObject() instanceof MethodEntry e
                        && e.tagStatus() == TagStatus.NEEDS_REVIEW) {
                    count++;
                }
            }
            return count;
        }

        private static String buildTooltip(MethodEntry entry) {
            StringBuilder sb = new StringBuilder(256);
            sb.append("<html><b>").append(escHtml(entry.discovered().fqcn()))
              .append("#").append(escHtml(entry.discovered().method())).append("</b>");
            if (entry.suggestion() != null) {
                sb.append("<br>AI: ").append(entry.suggestion().securityRelevant()
                        ? "security-relevant" : "not security-relevant");
                if (entry.suggestion().reason() != null) {
                    sb.append("<br><i>").append(escHtml(entry.suggestion().reason())).append("</i>");
                }
            }
            sb.append("</html>");
            return sb.toString();
        }

        private static String escHtml(String s) {
            return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
        }
    }
}
