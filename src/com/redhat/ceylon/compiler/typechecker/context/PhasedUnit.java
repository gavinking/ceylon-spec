package com.redhat.ceylon.compiler.typechecker.context;

import java.lang.ref.WeakReference;
import java.util.List;

import org.antlr.runtime.CommonToken;

import com.redhat.ceylon.compiler.typechecker.analyzer.AliasVisitor;
import com.redhat.ceylon.compiler.typechecker.analyzer.AnnotationVisitor;
import com.redhat.ceylon.compiler.typechecker.analyzer.ControlFlowVisitor;
import com.redhat.ceylon.compiler.typechecker.analyzer.DeclarationVisitor;
import com.redhat.ceylon.compiler.typechecker.analyzer.ExpressionVisitor;
import com.redhat.ceylon.compiler.typechecker.analyzer.LiteralVisitor;
import com.redhat.ceylon.compiler.typechecker.analyzer.LocalDeclarationVisitor;
import com.redhat.ceylon.compiler.typechecker.analyzer.ModuleManager;
import com.redhat.ceylon.compiler.typechecker.analyzer.ModuleVisitor;
import com.redhat.ceylon.compiler.typechecker.analyzer.RefinementVisitor;
import com.redhat.ceylon.compiler.typechecker.analyzer.SelfReferenceVisitor;
import com.redhat.ceylon.compiler.typechecker.analyzer.SpecificationVisitor;
import com.redhat.ceylon.compiler.typechecker.analyzer.SupertypeVisitor;
import com.redhat.ceylon.compiler.typechecker.analyzer.TypeArgumentVisitor;
import com.redhat.ceylon.compiler.typechecker.analyzer.TypeDeclarationVisitor;
import com.redhat.ceylon.compiler.typechecker.analyzer.TypeHierarchyVisitor;
import com.redhat.ceylon.compiler.typechecker.analyzer.TypeVisitor;
import com.redhat.ceylon.compiler.typechecker.io.VirtualFile;
import com.redhat.ceylon.compiler.typechecker.io.impl.Helper;
import com.redhat.ceylon.compiler.typechecker.model.Declaration;
import com.redhat.ceylon.compiler.typechecker.model.Module;
import com.redhat.ceylon.compiler.typechecker.model.Package;
import com.redhat.ceylon.compiler.typechecker.model.ProducedType;
import com.redhat.ceylon.compiler.typechecker.model.TypeDeclaration;
import com.redhat.ceylon.compiler.typechecker.model.Unit;
import com.redhat.ceylon.compiler.typechecker.tree.Tree;
import com.redhat.ceylon.compiler.typechecker.tree.Tree.ImportPath;
import com.redhat.ceylon.compiler.typechecker.tree.Tree.ModuleDescriptor;
import com.redhat.ceylon.compiler.typechecker.tree.Util;
import com.redhat.ceylon.compiler.typechecker.tree.Validator;
import com.redhat.ceylon.compiler.typechecker.tree.Visitor;
import com.redhat.ceylon.compiler.typechecker.util.AssertionVisitor;
import com.redhat.ceylon.compiler.typechecker.util.DeprecationVisitor;
import com.redhat.ceylon.compiler.typechecker.util.PrintVisitor;
import com.redhat.ceylon.compiler.typechecker.util.ReferenceCounter;
import com.redhat.ceylon.compiler.typechecker.util.StatisticsVisitor;
import com.redhat.ceylon.compiler.typechecker.util.UnitFactory;
import com.redhat.ceylon.compiler.typechecker.util.UsageVisitor;

/**
 * Represent a unit and each of the type checking phases
 *
 * @author Emmanuel Bernard <emmanuel@hibernate.org>
 */
public class PhasedUnit {
    private Tree.CompilationUnit compilationUnit;
    private Package pkg;
    private Unit unit;
    //must be the non qualified file name
    private String fileName;
    private WeakReference<ModuleManager> moduleManagerRef;
    private final String pathRelativeToSrcDir;
    private VirtualFile unitFile;
    private List<CommonToken> tokens;
    private ModuleVisitor moduleVisitor;
    private VirtualFile srcDir;
    private boolean treeValidated = false;
    private boolean declarationsScanned = false;
    private boolean declarationsWired = false;
    private boolean scanningDeclarations = false;
    private boolean typeDeclarationsScanned = false;
    private boolean refinementValidated = false;
    private boolean flowAnalyzed = false;
    private boolean fullyTyped = false;
    private boolean usageAnalyzed = false;
    private boolean literalsProcessed = false;
    private boolean moduleVisited = false;

    public VirtualFile getSrcDir() {
        return srcDir;
    }

    public PhasedUnit(VirtualFile unitFile, VirtualFile srcDir, Tree.CompilationUnit cu, 
            Package p, ModuleManager moduleManager, Context context, List<CommonToken> tokenStream) {
        this.compilationUnit = cu;
        this.pkg = p;
        this.unitFile = unitFile;
        this.srcDir = srcDir;
        this.fileName = unitFile.getName();
        this.pathRelativeToSrcDir = Helper.computeRelativePath(unitFile, srcDir);
        this.moduleManagerRef = new WeakReference<>(moduleManager);
        this.tokens = tokenStream;
        unit = createUnit();
        unit.setFilename(fileName);
        unit.setFullPath(unitFile.getPath());
        unit.setRelativePath(pathRelativeToSrcDir);
        unit.setPackage(pkg);
        pkg.removeUnit(unit);
        pkg.addUnit(unit);
        cu.setUnit(unit);
    }

    public PhasedUnit(PhasedUnit other) {
        this.compilationUnit = other.compilationUnit;
        this.pkg = other.pkg;
        this.unit = other.unit;
        this.fileName = other.fileName;
        this.moduleManagerRef = new WeakReference<>(other.moduleManagerRef.get());
        this.pathRelativeToSrcDir = other.pathRelativeToSrcDir;
        this.unitFile = other.unitFile;
        this.tokens = other.tokens;
        this.moduleVisitor = other.moduleVisitor;
        this.srcDir = other.srcDir;
        this.treeValidated = other.treeValidated;
        this.declarationsScanned = other.declarationsScanned;
        this.scanningDeclarations = other.scanningDeclarations;
        this.typeDeclarationsScanned = other.typeDeclarationsScanned;
        this.fullyTyped = other.fullyTyped;
        this.refinementValidated = other.refinementValidated;
        this.fullyTyped = other.fullyTyped;
        this.flowAnalyzed = other.flowAnalyzed;
    }

    @Deprecated
    protected PhasedUnit(VirtualFile unitFile, VirtualFile srcDir, Tree.CompilationUnit cu, 
            Package p, ModuleManager moduleManager, Context context) {
        this(unitFile, srcDir, cu, p, moduleManager, context, null);
    }
    
    protected boolean reuseExistingDescriptorModels() {
        return false;
    }
    
    public Module visitSrcModulePhase() {
        if ( ModuleManager.MODULE_FILE.equals(fileName) ||
                ModuleManager.PACKAGE_FILE.equals(fileName) ) {
            if (! moduleVisited) {
                moduleVisited = true;
                processLiterals();
                moduleVisitor = new ModuleVisitor(moduleManagerRef.get(), pkg);
                moduleVisitor.setCompleteOnlyAST(reuseExistingDescriptorModels());
                compilationUnit.visit(moduleVisitor);
                return moduleVisitor.getMainModule();
            }
        }
        return null;
    }

    protected Unit createUnit() {
        return new Unit();
    }
    
    public void visitRemainingModulePhase() {
        if ( moduleVisitor != null ) {
            moduleVisitor.setPhase(ModuleVisitor.Phase.REMAINING);
            compilationUnit.visit(moduleVisitor);
            moduleVisitor = null;
        }
    }
    
    public boolean isFullyTyped() {
        return fullyTyped;
    }

    public void setFullyTyped(boolean fullyTyped) {
        this.fullyTyped = fullyTyped;
    }
    
    public boolean isFlowAnalyzed() {
        return flowAnalyzed;
    }

    public void setFlowAnalyzed(boolean flowAnalyzed) {
        this.flowAnalyzed = flowAnalyzed;
    }

    public boolean isTreeValidated() {
        return treeValidated;
    }

    public void setTreeValidated(boolean treeValidated) {
        this.treeValidated = treeValidated;
    }

    public boolean isDeclarationsScanned() {
        return declarationsScanned;
    }

    public void setDeclarationsScanned(boolean declarationsScanned) {
        this.declarationsScanned = declarationsScanned;
    }
    
    public boolean isDeclarationsWired() {
        return declarationsWired;
    }
    
    public void setDeclarationsWired(boolean declarationsWired) {
        this.declarationsWired = declarationsWired;
    }

    public boolean isTypeDeclarationsScanned() {
        return typeDeclarationsScanned;
    }

    public void setTypeDeclarationsScanned(boolean typeDeclarationsScanned) {
        this.typeDeclarationsScanned = typeDeclarationsScanned;
    }

    public boolean isRefinementValidated() {
        return refinementValidated;
    }

    public void setRefinementValidated(boolean refinementValidated) {
        this.refinementValidated = refinementValidated;
    }

    public void validateTree() {
        //System.out.println("Validating tree for " + fileName);
        if (!treeValidated) {
            String fn = unit.getRelativePath();
            for (int i=0; i<fn.length(); i = fn.offsetByCodePoints(i, 1)) {
                int cp = fn.codePointAt(i);
                if (cp>127) {
                    compilationUnit.addUsageWarning("source file name has non-ASCII characters: " + fn);
                }
            }
            for (Unit u: unit.getPackage().getUnits()) {
                if (!u.equals(unit) && 
                        u.getFilename().equalsIgnoreCase(unit.getFilename())) {
                    if (u.getFilename().equals(unit.getFilename())) {
                        String errorMessage = "identical source files: " +
                                unit.getFullPath() + " and " + u.getFullPath();
                        if (u.getFilename().equals(ModuleManager.MODULE_FILE) ||
                                u.getFilename().equals(ModuleManager.PACKAGE_FILE)) {
                            errorMessage += " (a module/package descriptor should be defined only once, even in case of multiple source directories)";
                        }
                        compilationUnit.addError(errorMessage);                        
                    } else {
                        compilationUnit.addUsageWarning("source file names differ only by case: " +
                                unit.getFullPath() + " and " + u.getFullPath());
                    }
                }
            }
            compilationUnit.visit(new Validator());
            compilationUnit.visit(new Visitor() {
                @Override
                public void visit(ModuleDescriptor that) {
                    super.visit(that);
                    ImportPath importPath = that.getImportPath();
                    if (importPath != null) {
                        String moduleName = Util.formatPath(importPath.getIdentifiers());
                        ModuleManager moduleManager = moduleManagerRef.get();
                        if (moduleManager != null) {
                            for (Module otherModule : moduleManager.getCompiledModules()) {
                                String otherModuleName = otherModule.getNameAsString();
                                if (moduleName.startsWith(otherModuleName + ".") || 
                                        otherModuleName.startsWith(moduleName + ".")) {
                                    StringBuilder error = new StringBuilder("Found two modules within the same hierarchy: '");
                                    error.append( otherModule.getNameAsString() )
                                    .append( "' and '" )
                                    .append( moduleName )
                                    .append("'");
                                    that.addError(error.toString());
                                }
                            }
                        }
                    }
                }
            });
            treeValidated = true;
        }
    }

    public void scanDeclarations() {
        if (!declarationsScanned) {
            processLiterals();
            scanningDeclarations = true;
            //System.out.println("Scan declarations for " + fileName);
            UnitFactory unitFactory = new UnitFactory() {
                @Override
                public Unit createUnit() {
                    return PhasedUnit.this.createUnit();
                }
            };
            DeclarationVisitor dv = new DeclarationVisitor(pkg, fileName,
            		unitFile.getPath(), pathRelativeToSrcDir, unitFactory);
            compilationUnit.visit(dv);
            unit = dv.getCompilationUnit();
            
            LocalDeclarationVisitor ldv = new LocalDeclarationVisitor();
            compilationUnit.visit(ldv);
            
            declarationsScanned = true;
            scanningDeclarations = false;
        }
    }

	private void processLiterals() {
		if (!literalsProcessed) {
			compilationUnit.visit(new LiteralVisitor());
			literalsProcessed = true;
		}
	}
	
	public void wireTypeDeclarations() {
        if (!declarationsWired) {
            compilationUnit.visit(new TypeDeclarationVisitor());
            declarationsWired = true;
        }
	}

    public void scanTypeDeclarations() {
        if (!typeDeclarationsScanned) {
            //System.out.println("Scan type declarations for " + fileName);
            compilationUnit.visit(new TypeVisitor());
            typeDeclarationsScanned = true;
        }
    }

    public synchronized void validateRefinement() {
        if (! refinementValidated) {
            ProducedType.depth.set(0);
            //System.out.println("Validate member refinement for " + fileName);
        	compilationUnit.visit(new AliasVisitor());
            compilationUnit.visit(new SupertypeVisitor()); //TODO: move to a new phase!
            compilationUnit.visit(new RefinementVisitor());
            refinementValidated = true;
        }
    }

    public synchronized void analyseTypes() {
        if (! fullyTyped) {
            ProducedType.depth.set(-100);
            //System.out.println("Run analysis phase for " + fileName);
            compilationUnit.visit(new ExpressionVisitor());
            compilationUnit.visit(new AnnotationVisitor());
            compilationUnit.visit(new TypeArgumentVisitor());
            compilationUnit.visit(new TypeHierarchyVisitor());
            fullyTyped = true;
        }
    }
    
    public synchronized void analyseFlow() {
        if (! flowAnalyzed) {
            //System.out.println("Validate control flow for " + fileName);
            compilationUnit.visit(new ControlFlowVisitor());
            //System.out.println("Validate self references for " + fileName);
            //System.out.println("Validate specification for " + fileName);
            for (Declaration d: unit.getDeclarations()) {
                compilationUnit.visit(new SpecificationVisitor(d));
//                if (d instanceof TypedDeclaration && !(d instanceof Setter)) {
//                    compilationUnit.visit(new ValueVisitor((TypedDeclaration) d));
//                }
                if (d instanceof TypeDeclaration) {
                    compilationUnit.visit(new SelfReferenceVisitor((TypeDeclaration) d));
                }
            }
            flowAnalyzed = true;
        }
    }

    public synchronized void analyseUsage() {
        if (! usageAnalyzed) {
            ReferenceCounter rc = new ReferenceCounter();
            compilationUnit.visit(rc);
            compilationUnit.visit(new UsageVisitor(rc));
            compilationUnit.visit(new DeprecationVisitor());
            usageAnalyzed = true;
        }
    }

    public void generateStatistics(StatisticsVisitor statsVisitor) {
        compilationUnit.visit(statsVisitor);
    }
    
    public void runAssertions(AssertionVisitor av) {
        //System.out.println("Running assertions for " + fileName);
        compilationUnit.visit(av);
    }

    public void display() {
        System.out.println("Displaying " + fileName);
        compilationUnit.visit(new PrintVisitor());
    }
    
    public Package getPackage() {
        return pkg;
    }
    
    public Unit getUnit() {
        return unit;
    }

    public List<Declaration> getDeclarations() {
        if (!declarationsScanned) {
            scanDeclarations();
        }
        return unit.getDeclarations();
    }

    public String getPathRelativeToSrcDir() {
        return pathRelativeToSrcDir;
    }

    public VirtualFile getUnitFile() {
        return unitFile;
    }

    @Override
    public String toString() {
        final StringBuilder sb = new StringBuilder();
        sb.append("PhasedUnit");
        sb.append("{filename=").append(fileName);
        sb.append(", compilationUnit=").append(unit);
        sb.append(", pkg=").append(pkg);
        sb.append('}');
        return sb.toString();
    }

    public Tree.CompilationUnit getCompilationUnit() {
        return compilationUnit;
    }

    public List<CommonToken> getTokens() {
        return tokens;
    }

    public boolean isScanningDeclarations() {
        return scanningDeclarations;
    }
}
