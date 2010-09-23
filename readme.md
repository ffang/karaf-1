# Setup

If you want to be able to fetch the latest Apache updates into this git clone then you need to initialize it
some 'git svn' commands which you can run by executing following script:

    ./svn-init.sh

Then check out apache branch you want to update and run `git svn rebase` to update it.  For example:

    git checkout origin/trunk -b trunk
    git svn rebase

# Branches

## Apache Branches

We should not directly commit to these branches. They should only be updated using 'git svn rebase'

* trunk : mirror of apache trunk branch 

## FuseSource Branches

This is where we do our development, cherry picking, tag and releasing of FuseSource version of the product.

* master : holds documentation about this git repo


