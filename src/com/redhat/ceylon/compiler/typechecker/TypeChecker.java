package com.redhat.ceylon.compiler.typechecker;

import static java.lang.System.nanoTime;
import static java.lang.System.out;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Phaser;

import com.redhat.ceylon.cmr.api.RepositoryManager;
import com.redhat.ceylon.compiler.typechecker.analyzer.ModuleValidator;
import com.redhat.ceylon.compiler.typechecker.context.Context;
import com.redhat.ceylon.compiler.typechecker.context.PhasedUnit;
import com.redhat.ceylon.compiler.typechecker.context.PhasedUnits;
import com.redhat.ceylon.compiler.typechecker.io.VFS;
import com.redhat.ceylon.compiler.typechecker.io.VirtualFile;
import com.redhat.ceylon.compiler.typechecker.model.Module;
import com.redhat.ceylon.compiler.typechecker.tree.Message;
import com.redhat.ceylon.compiler.typechecker.util.AssertionVisitor;
import com.redhat.ceylon.compiler.typechecker.util.ModuleManagerFactory;
import com.redhat.ceylon.compiler.typechecker.util.StatisticsVisitor;

/**
 * Executes type checking upon construction and retrieve a CompilationUnit object for a given File.
 *
 * @author Emmanuel Bernard <emmanuel@hibernate.org>
 */
//TODO make an interface?
public class TypeChecker {

    public static final String LANGUAGE_MODULE_VERSION = "0.3";

    private final boolean verbose;
    private final boolean statistics;
    private final int threads;
    private final Context context;
    private final PhasedUnits phasedUnits;
    private volatile List<PhasedUnits> phasedUnitsOfDependencies;
    private final boolean verifyDependencies;
    private final AssertionVisitor assertionVisitor;
    private final StatisticsVisitor statsVisitor;

    //package level
    TypeChecker(VFS vfs, List<VirtualFile> srcDirectories, RepositoryManager repositoryManager, boolean verifyDependencies,
            AssertionVisitor assertionVisitor, ModuleManagerFactory moduleManagerFactory, boolean verbose, boolean statistics, 
            int threads, List<String> moduleFilters) {
        long start = System.nanoTime();
        this.verbose = verbose;
        this.statistics = statistics;
        this.threads = threads;
        this.context = new Context(repositoryManager, vfs);
        this.phasedUnits = new PhasedUnits(context, moduleManagerFactory);
        this.verifyDependencies = verifyDependencies;
        this.assertionVisitor = assertionVisitor;
        statsVisitor = new StatisticsVisitor();
        phasedUnits.setModuleFilters(moduleFilters);
        phasedUnits.parseUnits(srcDirectories);
        long time = System.nanoTime()-start;
        if(verbose||statistics)
        	System.out.println("Parsed in " + time/1000000 + " ms");
    }

    public PhasedUnits getPhasedUnits() {
        return phasedUnits;
    }
    
    public List<PhasedUnits> getPhasedUnitsOfDependencies() {
        return phasedUnitsOfDependencies;
    }
    
    public void setPhasedUnitsOfDependencies(
            List<PhasedUnits> phasedUnitsOfDependencies) {
        this.phasedUnitsOfDependencies = phasedUnitsOfDependencies;
    }

    public Context getContext() {
        return context;
    }

    /**
     * Return the PhasedUnit for a given relative path.
     * The path is relative to the source directory
     * eg ceylon/language/Object.ceylon
     */
    public PhasedUnit getPhasedUnitFromRelativePath(String relativePath) {
        PhasedUnit phasedUnit = phasedUnits.getPhasedUnitFromRelativePath(relativePath);
        if (phasedUnit == null) {
            for (PhasedUnits units : phasedUnitsOfDependencies) {
                phasedUnit = units.getPhasedUnitFromRelativePath(relativePath);
                if (phasedUnit != null) {
                    return phasedUnit;
                }
            }
            return null;
        }
        else {
            return phasedUnit;
        }
    }

    public PhasedUnit getPhasedUnit(VirtualFile file) {
        PhasedUnit phasedUnit = phasedUnits.getPhasedUnit(file);
        if (phasedUnit == null) {
            for (PhasedUnits units : phasedUnitsOfDependencies) {
                phasedUnit = units.getPhasedUnit(file);
                if (phasedUnit != null) {
                    return phasedUnit;
                }
            }
            return null;
        }
        else {
            return phasedUnit;
        }
    }

    /*
     * Return the CompilationUnit for a given file.
     * May return null of the CompilationUnit has not been parsed.
     */
    /*public Tree.CompilationUnit getCompilationUnit(File file) {
        final PhasedUnit phasedUnit = phasedUnits.getPhasedUnit( context.getVfs().getFromFile(file) );
        return phasedUnit.getCompilationUnit();
    }*/

    public void process() throws RuntimeException {
        long start = nanoTime();
        executePhases(phasedUnits, false);
        long time = nanoTime()-start;
        if(verbose||statistics)
        	out.println("Type checked in " + time/1000000 + " ms");
    }

    private void executePhases(PhasedUnits phasedUnits, boolean forceSilence) {
        long start = nanoTime();

        final List<PhasedUnit> listOfUnits = phasedUnits.getPhasedUnits();

        final Phaser p = new Phaser(1);
        if (verbose||statistics) {
            out.println("typechecking " + listOfUnits.size() + " units");
        }
        final ExecutorService es = Executors.newFixedThreadPool(threads);
        for (int i=0; i<threads; i++) {
            p.register();
            final int ii=i;
            es.execute(new Runnable() {
                public void run() {
                    p.arriveAndAwaitAdvance();
                    for (int j=ii; j<listOfUnits.size(); j+=threads) {
                        listOfUnits.get(j).validateTree();
                        listOfUnits.get(j).scanDeclarations();
                    }
                    p.arriveAndAwaitAdvance();
                    for (int j=ii; j<listOfUnits.size(); j+=threads) {
                        listOfUnits.get(j).scanTypeDeclarations();
                    }
                    p.arriveAndAwaitAdvance();
                    for (int j=ii; j<listOfUnits.size(); j+=threads) {
                        listOfUnits.get(j).validateRefinement();
                    }
                    p.arriveAndAwaitAdvance();
                    for (int j=ii; j<listOfUnits.size(); j+=threads) {
                        listOfUnits.get(j).analyseTypes();
                    }
                    p.arriveAndAwaitAdvance();
                    for (int j=ii; j<listOfUnits.size(); j+=threads) {
                        listOfUnits.get(j).analyseFlow();
                    }
                    p.arriveAndDeregister();
                }
            });
        }

        phasedUnits.getModuleManager().prepareForTypeChecking();
        phasedUnits.visitModules();
        phasedUnits.getModuleManager().modulesVisited();

        //By now the language module version should be known (as local)
        //or we should use the default one.
        Module languageModule = context.getModules().getLanguageModule();
        if (languageModule.getVersion() == null) {
            languageModule.setVersion(LANGUAGE_MODULE_VERSION);
        }

        final ModuleValidator moduleValidator = new ModuleValidator(context, phasedUnits);
        if (verifyDependencies) {
            moduleValidator.verifyModuleDependencyTree();
        }
        phasedUnitsOfDependencies = moduleValidator.getPhasedUnitsOfDependencies();

        p.arriveAndAwaitAdvance();
        
        while (p.getPhase()<6) {
            start = nanoTime();
            p.arriveAndAwaitAdvance();
            if (verbose||statistics) {
                out.println("step " + p.getPhase() + ": " + (nanoTime()-start)/1000000);
            }
        }
        es.shutdown();
        if (verbose||statistics) {
            out.println("finished all");
        }

        /*if (statistics) out.println("step 0: " + (nanoTime()-start)/1000000);
        start = nanoTime();
        for (PhasedUnit pu : listOfUnits) {
            pu.validateTree();
            pu.scanDeclarations();
        }
        if (statistics) out.println("step 1: " + (nanoTime()-start)/1000000);
        start = nanoTime();
        for (PhasedUnit pu : listOfUnits) {
            pu.scanTypeDeclarations();
        }
        if (statistics) out.println("step 2: " + (nanoTime()-start)/1000000);
        start = nanoTime();
        for (PhasedUnit pu: listOfUnits) {
            pu.validateRefinement();
        }
        if (statistics) out.println("step 3: " + (nanoTime()-start)/1000000);
        for (PhasedUnit pu: listOfUnits) {
            pu.analyseTypes();
        }
        if (statistics) out.println("step 4: " + (nanoTime()-start)/1000000);
        start = nanoTime();
        for (PhasedUnit pu: listOfUnits) {
            pu.analyseFlow();
        }
        if (statistics) out.println("step 5: " + (nanoTime()-start)/1000000);*/

        if (!forceSilence) {
            for (PhasedUnit pu : listOfUnits) {
                if (verbose) {
                    pu.display();
                }
                pu.generateStatistics(statsVisitor);
                pu.runAssertions(assertionVisitor);
            }
            if(verbose||statistics)
                statsVisitor.print();
            assertionVisitor.print(verbose);
        }

    }
    
    public int getErrors(){
    	return assertionVisitor.getErrors();
    }

    public int getWarnings(){
    	return assertionVisitor.getWarnings();
    }
    
    public List<Message> getMessages(){
    	return assertionVisitor.getFoundErrors();
    }
}
