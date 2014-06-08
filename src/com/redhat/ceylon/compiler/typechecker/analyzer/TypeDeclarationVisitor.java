package com.redhat.ceylon.compiler.typechecker.analyzer;

import static com.redhat.ceylon.compiler.typechecker.analyzer.Util.getTypeDeclaration;
import static com.redhat.ceylon.compiler.typechecker.tree.Util.name;

import com.redhat.ceylon.compiler.typechecker.model.Class;
import com.redhat.ceylon.compiler.typechecker.model.Interface;
import com.redhat.ceylon.compiler.typechecker.model.TypeDeclaration;
import com.redhat.ceylon.compiler.typechecker.model.Unit;
import com.redhat.ceylon.compiler.typechecker.tree.Node;
import com.redhat.ceylon.compiler.typechecker.tree.Tree;
import com.redhat.ceylon.compiler.typechecker.tree.Visitor;

public class TypeDeclarationVisitor extends Visitor {
    
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
        current.setExtendedTypeDeclaration(that.getUnit().getBasicDeclaration());
        super.visit(that);
        current = old;
    }
    
    public void visit(Tree.ObjectArgument that) {
        TypeDeclaration old = current;
        current = that.getDeclarationModel().getTypeDeclaration();
        current.setExtendedTypeDeclaration(that.getUnit().getBasicDeclaration());
        super.visit(that);
        current = old;
    }
    
    public void visit(Tree.TypeParameterDeclaration that) {
        that.getDeclarationModel().setExtendedTypeDeclaration(that.getUnit().getAnythingDeclaration());
    }
    
    public void visit(Tree.TypeDeclaration that) {
        TypeDeclaration old = current;
        current = that.getDeclarationModel();
        Unit unit = that.getUnit();
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
        current.setExtendedTypeDeclaration(d);
        super.visit(that);
    }
    
    @Override
    public void visit(Tree.SatisfiedTypes that) {
        for (Tree.StaticType t: that.getTypes()) {
            TypeDeclaration d = getDeclaration(t);
            current.getSatisfiedTypeDeclarations().add(d);
        }
        super.visit(that);
    }

    @Override
    public void visit(Tree.TypeSpecifier that) {
        TypeDeclaration d = getDeclaration(that.getType());
        current.setExtendedTypeDeclaration(d);
        super.visit(that);
    }

    @Override
    public void visit(Tree.ClassSpecifier that) {
        TypeDeclaration d = getDeclaration(that.getType());
        current.setExtendedTypeDeclaration(d);
        super.visit(that);
    }
}
