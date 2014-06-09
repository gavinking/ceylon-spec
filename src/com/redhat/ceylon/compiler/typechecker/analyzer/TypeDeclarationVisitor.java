package com.redhat.ceylon.compiler.typechecker.analyzer;

import static com.redhat.ceylon.compiler.typechecker.analyzer.Util.declaredInPackage;
import static com.redhat.ceylon.compiler.typechecker.analyzer.Util.getTypeDeclaration;
import static com.redhat.ceylon.compiler.typechecker.model.Util.notOverloaded;
import static com.redhat.ceylon.compiler.typechecker.tree.Util.formatPath;
import static com.redhat.ceylon.compiler.typechecker.tree.Util.name;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import com.redhat.ceylon.compiler.typechecker.model.Class;
import com.redhat.ceylon.compiler.typechecker.model.Declaration;
import com.redhat.ceylon.compiler.typechecker.model.Import;
import com.redhat.ceylon.compiler.typechecker.model.ImportList;
import com.redhat.ceylon.compiler.typechecker.model.Interface;
import com.redhat.ceylon.compiler.typechecker.model.Module;
import com.redhat.ceylon.compiler.typechecker.model.ModuleImport;
import com.redhat.ceylon.compiler.typechecker.model.Package;
import com.redhat.ceylon.compiler.typechecker.model.TypeDeclaration;
import com.redhat.ceylon.compiler.typechecker.model.TypedDeclaration;
import com.redhat.ceylon.compiler.typechecker.model.Unit;
import com.redhat.ceylon.compiler.typechecker.parser.CeylonLexer;
import com.redhat.ceylon.compiler.typechecker.tree.Node;
import com.redhat.ceylon.compiler.typechecker.tree.Tree;
import com.redhat.ceylon.compiler.typechecker.tree.Visitor;

public class TypeDeclarationVisitor extends Visitor {
    
    private Unit unit;
    
    @Override public void visit(Tree.CompilationUnit that) {
        unit = that.getUnit();
        super.visit(that);
        HashSet<String> set = new HashSet<String>();
        for (Tree.Import im: that.getImportList().getImports()) {
            Tree.ImportPath ip = im.getImportPath();
            if (ip!=null) {
                String mp = formatPath(ip.getIdentifiers());
                if (!set.add(mp)) {
                    ip.addError("duplicate import: " + mp);
                }
            }
        }
    }
    
    @Override
    public void visit(Tree.Import that) {
        Package importedPackage = getPackage(that.getImportPath());
        if (importedPackage!=null) {
            that.getImportPath().setModel(importedPackage);
            Tree.ImportMemberOrTypeList imtl = that.getImportMemberOrTypeList();
            if (imtl!=null) {
                ImportList il = imtl.getImportList();
                il.setImportedScope(importedPackage);
                Set<String> names = new HashSet<String>();
                for (Tree.ImportMemberOrType member: imtl.getImportMemberOrTypes()) {
                    names.add(importMember(member, importedPackage, il));
                }
                if (imtl.getImportWildcard()!=null) {
                    importAllMembers(importedPackage, names, il);
                } 
                else if (imtl.getImportMemberOrTypes().isEmpty()) {
                    imtl.addError("empty import list");
                }
            }
        }
    }
    
    private void importAllMembers(Package importedPackage, 
            Set<String> ignoredMembers, ImportList il) {
        for (Declaration dec: importedPackage.getMembers()) {
            if (dec.isShared() && !dec.isAnonymous() && 
                    !ignoredMembers.contains(dec.getName()) &&
                    !isNonimportable(importedPackage, dec.getName())) {
                addWildcardImport(il, dec);
            }
        }
    }
    
    private void importAllMembers(TypeDeclaration importedType, 
            Set<String> ignoredMembers, ImportList til) {
        for (Declaration dec: importedType.getMembers()) {
            if (dec.isShared() && dec.isStaticallyImportable() && 
                    !dec.isAnonymous() && 
                    !ignoredMembers.contains(dec.getName())) {
                addWildcardImport(til, dec);
            }
        }
    }
    
    private void addWildcardImport(ImportList il, Declaration dec) {
        if (!hidesToplevel(dec)) {
            Import i = new Import();
            i.setAlias(dec.getName());
            i.setDeclaration(dec);
            i.setWildcardImport(true);
            addWildcardImport(il, dec, i);
        }
    }
    
    private void addWildcardImport(ImportList il, Declaration dec, Import i) {
        if (notOverloaded(dec)) {
            String alias = i.getAlias();
            if (alias!=null) {
                Import o = unit.getImport(dec.getName());
                if (o!=null && o.isWildcardImport()) {
                    if (o.getDeclaration().equals(dec)) {
                        //this case only happens in the IDE,
                        //due to reuse of the Unit
                        unit.getImports().remove(o);
                        il.getImports().remove(o);
                    }
                    else {
                        i.setAmbiguous(true);
                        o.setAmbiguous(true);
                    }
                }
                unit.getImports().add(i);
                il.getImports().add(i);
            }
        }
    }
    
    public static Module getModule(Tree.ImportPath path) {
        if (path!=null && !path.getIdentifiers().isEmpty()) {
            String nameToImport = formatPath(path.getIdentifiers());
            Module module = path.getUnit().getPackage().getModule();
            Package pkg = module.getPackage(nameToImport);
            if (pkg != null) {
                Module mod = pkg.getModule();
                if (!pkg.getNameAsString().equals(mod.getNameAsString())) {
                    path.addError("not a module: " + nameToImport);
                    return null;
                }
                if (mod.equals(module)) {
                    return mod;
                }
                //check that the package really does belong to
                //an imported module, to work around bug where
                //default package thinks it can see stuff in
                //all modules in the same source dir
                Set<Module> visited = new HashSet<Module>();
                for (ModuleImport mi: module.getImports()) {
                    if (findModuleInTransitiveImports(mi.getModule(), 
                            mod, visited)) {
                        return mod; 
                    }
                }
            }
            path.addError("module not found in imported modules: " + 
                    nameToImport, 7000);
        }
        return null;
    }
    
    public static Package getPackage(Tree.ImportPath path) {
        if (path!=null && !path.getIdentifiers().isEmpty()) {
            String nameToImport = formatPath(path.getIdentifiers());
            Module module = path.getUnit().getPackage().getModule();
            Package pkg = module.getPackage(nameToImport);
            if (pkg != null) {
                if (pkg.getModule().equals(module)) {
                    return pkg;
                }
                if (!pkg.isShared()) {
                    path.addError("imported package is not shared: " + 
                            nameToImport);
                }
//                if (module.isDefault() && 
//                        !pkg.getModule().isDefault() &&
//                        !pkg.getModule().getNameAsString()
//                            .equals(Module.LANGUAGE_MODULE_NAME)) {
//                    path.addError("package belongs to a module and may not be imported by default module: " +
//                            nameToImport);
//                }
                //check that the package really does belong to
                //an imported module, to work around bug where
                //default package thinks it can see stuff in
                //all modules in the same source dir
                Set<Module> visited = new HashSet<Module>();
                for (ModuleImport mi: module.getImports()) {
                    if (findModuleInTransitiveImports(mi.getModule(), 
                            pkg.getModule(), visited)) {
                        return pkg; 
                    }
                }
            }
            String help;
            if(module.isDefault())
                help = " (define a module and add module import to its module descriptor)";
            else
                help = " (add module import to module descriptor of " +
                        module.getNameAsString() + ")";
            path.addError("package not found in imported modules: " + 
                    nameToImport + help, 7000);
        }
        return null;
    }
    
//    private boolean hasName(List<Tree.Identifier> importPath, Package mp) {
//        if (mp.getName().size()==importPath.size()) {
//            for (int i=0; i<mp.getName().size(); i++) {
//                if (!mp.getName().get(i).equals(name(importPath.get(i)))) {
//                    return false;
//                }
//            }
//            return true;
//        }
//        else {
//            return false;
//        }
//    }
    
    private static boolean findModuleInTransitiveImports(Module moduleToVisit, 
            Module moduleToFind, Set<Module> visited) {
        if (!visited.add(moduleToVisit))
            return false;
        if (moduleToVisit.equals(moduleToFind))
            return true;
        for (ModuleImport imp : moduleToVisit.getImports()) {
            // skip non-exported modules
            if (!imp.isExport())
                continue;
            if (findModuleInTransitiveImports(imp.getModule(), moduleToFind, visited))
                return true;
        }
        return false;
    }
    
    private boolean hidesToplevel(Declaration dec) {
        for (Declaration d: unit.getDeclarations()) {
            String n = d.getName();
            if (d.isToplevel() && n!=null && 
                    dec.getName().equals(n)) {
                return true;
            }
        }
        return false;
    }
    
    private boolean checkForHiddenToplevel(Tree.Identifier id, Import i, Tree.Alias alias) {
        for (Declaration d: unit.getDeclarations()) {
            String n = d.getName();
            if (d.isToplevel() && n!=null && 
                    i.getAlias().equals(n)) {
                if (alias==null) {
                    id.addError("toplevel declaration with this name declared in this unit: " + n);
                }
                else {
                    alias.addError("toplevel declaration with this name declared in this unit: " + n);
                }
                return true;
            }
        }
        return false;
    }
    
    private void importMembers(Tree.ImportMemberOrType member, Declaration d) {
        Tree.ImportMemberOrTypeList imtl = member.getImportMemberOrTypeList();
        if (imtl!=null) {
            if (d instanceof TypeDeclaration) {
                Set<String> names = new HashSet<String>();
                ImportList til = imtl.getImportList();
                TypeDeclaration td = (TypeDeclaration) d;
                til.setImportedScope(td);
                for (Tree.ImportMemberOrType submember: imtl.getImportMemberOrTypes()) {
                    names.add(importMember(submember, td, til));
                }
                if (imtl.getImportWildcard()!=null) {
                    importAllMembers(td, names, til);
                }
                else if (imtl.getImportMemberOrTypes().isEmpty()) {
                    imtl.addError("empty import list");
                }
            }
            else {
                imtl.addError("member alias list must follow a type");
            }
        }
    }
    
    private void checkAliasCase(Tree.Alias alias, Declaration d) {
        if (alias!=null) {
            Tree.Identifier id = alias.getIdentifier();
            int tt = id.getToken().getType();
            if (d instanceof TypeDeclaration &&
                    tt!=CeylonLexer.UIDENTIFIER) {
                id.addError("imported type should have uppercase alias: " +
                        d.getName());
            }
            else if (d instanceof TypedDeclaration &&
                    tt!=CeylonLexer.LIDENTIFIER) {
                id.addError("imported member should have lowercase alias: " +
                        d.getName());
            }
        }
    }
    
    private String importMember(Tree.ImportMemberOrType member,
            Package importedPackage, ImportList il) {
        Tree.Identifier id = member.getIdentifier();
        if (id==null) {
            return null;
        }
        Import i = new Import();
        member.setImportModel(i);
        Tree.Alias alias = member.getAlias();
        String name = name(id);
        if (alias==null) {
            i.setAlias(name);
        }
        else {
            i.setAlias(name(alias.getIdentifier()));
        }
        if (isNonimportable(importedPackage, name)) {
            id.addError("root type may not be imported");
            return name;
        }        
        Declaration d = importedPackage.getMember(name, null, false);
        if (d==null) {
            id.addError("imported declaration not found: " + 
                    name, 100);
            unit.getUnresolvedReferences().add(id);
        }
        else {
            if (!declaredInPackage(d, unit)) {
                if (!d.isShared()) {
                    id.addError("imported declaration is not shared: " +
                            name, 400);
                }
                else if (d.isPackageVisibility()) {
                    id.addError("imported package private declaration is not visible: " +
                            name);
                }
                else if (d.isProtectedVisibility()) {
                    id.addError("imported protected declaration is not visible: " +
                            name);
                }
            }
            i.setDeclaration(d);
            member.setDeclarationModel(d);
            if (il.hasImport(d)) {
                id.addError("already imported: " + name);
            }
            else if (!checkForHiddenToplevel(id, i, alias)) {
                addImport(member, il, i);
            }
            checkAliasCase(alias, d);
        }
        importMembers(member, d);
        return name;
    }
    
    private String importMember(Tree.ImportMemberOrType member, 
            TypeDeclaration td, ImportList il) {
        Tree.Identifier id = member.getIdentifier();
        if (id==null) {
            return null;
        }
        Import i = new Import();
        member.setImportModel(i);
        Tree.Alias alias = member.getAlias();
        String name = name(id);
        if (alias==null) {
            i.setAlias(name);
        }
        else {
            i.setAlias(name(alias.getIdentifier()));
        }
        Declaration m = td.getMember(name, null, false);
        if (m==null) {
            id.addError("imported declaration not found: " + 
                    name + " of " + td.getName(), 100);
            unit.getUnresolvedReferences().add(id);
        }
        else {
            for (Declaration d: m.getContainer().getMembers()) {
                if (d.getName().equals(name) && !d.sameKind(m)) {
                    //crazy interop cases like isOpen() + open()
                    id.addError("ambiguous member declaration: " +
                            name + " of " + td.getName());
                    return null;
                }
            }
            if (!m.isShared()) {
                id.addError("imported declaration is not shared: " +
                        name + " of " + td.getName(), 400);
            }
            else if (!declaredInPackage(m, unit)) {
                if (m.isPackageVisibility()) {
                    id.addError("imported package private declaration is not visible: " +
                            name + " of " + td.getName());
                }
                else if (m.isProtectedVisibility()) {
                    id.addError("imported protected declaration is not visible: " +
                            name + " of " + td.getName());
                }
            }
            if (!m.isStaticallyImportable()) {
                i.setTypeDeclaration(td);
                if (alias==null) {
                    member.addError("does not specify an alias");
                }
            }
            i.setDeclaration(m);
            member.setDeclarationModel(m);
            if (il.hasImport(m)) {
                id.addError("already imported: " +
                        name + " of " + td.getName());
            }
            else {
                if (m.isStaticallyImportable()) {
                    if (!checkForHiddenToplevel(id, i, alias)) {
                        addImport(member, il, i);
                    }
                }
                else {
                    addMemberImport(member, il, i);
                }
            }
            checkAliasCase(alias, m);
        }
        importMembers(member, m);
        //imtl.addError("member aliases may not have member aliases");
        return name;
    }
    
    private void addImport(Tree.ImportMemberOrType member, ImportList il,
            Import i) {
        String alias = i.getAlias();
        if (alias!=null) {
            Declaration d = i.getDeclaration();
            Map<String, String> mods = unit.getModifiers();
            if (mods.containsValue(alias) &&
                    (!d.getUnit().getPackage().getNameAsString()
                            .equals(Module.LANGUAGE_MODULE_NAME) ||
                    !mods.containsKey(d.getName()))) {
                member.addError("import hides a language modifier: " + alias);
            }
            else {
                Import o = unit.getImport(alias);
                if (o==null) {
                    unit.getImports().add(i);
                    il.getImports().add(i);
                }
                else if (o.isWildcardImport()) {
                    unit.getImports().remove(o);
                    il.getImports().remove(o);
                    unit.getImports().add(i);
                    il.getImports().add(i);
                }
                else {
                    member.addError("duplicate import alias: " + alias);
                }
            }
        }
    }
    
    private void addMemberImport(Tree.ImportMemberOrType member, ImportList il,
            Import i) {
        String alias = i.getAlias();
        if (alias!=null) {
            if (il.getImport(alias)==null) {
                unit.getImports().add(i);
                il.getImports().add(i);
            }
            else {
                member.addError("duplicate member import alias: " + alias);
            }
        }
    }
    
    private boolean isNonimportable(Package pkg, String name) {
        return pkg.getQualifiedNameString().equals("java.lang") &&
                ("Object".equals(name) ||
                 "Throwable".equals(name) ||
                 "Exception".equals(name) ||
                 "Error".equals(name));
    }
    
    private TypeDeclaration current;
    
    private TypeDeclaration getDeclaration(Tree.StaticType t) {
        Unit unit = t.getUnit();
        if  (t instanceof Tree.BaseType) {
            Tree.Identifier id = ((Tree.BaseType) t).getIdentifier();
            return getTypeDeclaration(t.getScope(), 
                    name(id), null, false, unit);
        }
        else if (t instanceof Tree.FunctionType) {
            return unit.getCallableDeclaration();
        }
        else if (t instanceof Tree.EntryType) {
            return unit.getEntryDeclaration();
        }
        else if (t instanceof Tree.IterableType) {
            return unit.getIterableDeclaration();
        }
        else if (t instanceof Tree.SequenceType) {
            return unit.getSequentialDeclaration();
        }
        else if (t instanceof Tree.TupleType) {
            Tree.TupleType tt = (Tree.TupleType) t;
            if (tt.getChildren().isEmpty()) {
                return unit.getEmptyDeclaration();
            }
            else {
                Node child = tt.getChildren().get(0);
                if (child instanceof Tree.SequencedType) {
                    if (((Tree.SequencedType) child).getAtLeastOne()) {
                        return unit.getSequenceDeclaration();
                    }
                    else {
                        return unit.getSequentialDeclaration();
                    }
                }
                else {
                    return unit.getTupleDeclaration();
                }
            }
        }
        else {
            return null;
        }
    }
    
    public void visit(Tree.ObjectDefinition that) {
        TypeDeclaration old = current;
        current = that.getDeclarationModel().getTypeDeclaration();
        current.setExtendedTypeDeclaration(unit.getBasicDeclaration());
        super.visit(that);
        current = old;
    }
    
    public void visit(Tree.ObjectArgument that) {
        TypeDeclaration old = current;
        current = that.getDeclarationModel().getTypeDeclaration();
        current.setExtendedTypeDeclaration(unit.getBasicDeclaration());
        super.visit(that);
        current = old;
    }
    
    public void visit(Tree.TypeParameterDeclaration that) {
        that.getDeclarationModel().setExtendedTypeDeclaration(unit.getAnythingDeclaration());
    }
    
    public void visit(Tree.TypeDeclaration that) {
        TypeDeclaration old = current;
        current = that.getDeclarationModel();
        if (current instanceof Class) {
            if (!current.equals(unit.getAnythingDeclaration())) {
                current.setExtendedTypeDeclaration(unit.getBasicDeclaration());
            }
        }
        else if (current instanceof Interface) {
            current.setExtendedTypeDeclaration(unit.getObjectDeclaration());
        }
        /*else {
            current.setExtendedTypeDeclaration(that.getUnit().getAnythingDeclaration());
        }*/
        super.visit(that);
        current = old;
    }
    
    @Override
    public void visit(Tree.ExtendedType that) {
        TypeDeclaration d = getDeclaration(that.getType());
        if (d!=current && d!=null) {
            current.setExtendedTypeDeclaration(d);
        }
        super.visit(that);
    }
    
    @Override
    public void visit(Tree.SatisfiedTypes that) {
        for (Tree.StaticType t: that.getTypes()) {
            TypeDeclaration d = getDeclaration(t);
            if (d!=current && d!=null) {
                current.getSatisfiedTypeDeclarations().add(d);
            }
        }
        super.visit(that);
    }

    @Override
    public void visit(Tree.TypeSpecifier that) {
        TypeDeclaration d = getDeclaration(that.getType());
        if (d!=current && d!=null) {
            current.setExtendedTypeDeclaration(d);
        }
        super.visit(that);
    }

    @Override
    public void visit(Tree.ClassSpecifier that) {
        TypeDeclaration d = getDeclaration(that.getType());
        if (d!=current && d!=null) {
            current.setExtendedTypeDeclaration(d);
        }
        super.visit(that);
    }
}
