PROJECT_NAME=karaf
REPOSITORY="git://git.apache.org/karaf.git"
REMOTE=apache

echo "Configuring $REPOSITORY as remote $REMOTE"
git remote add $REMOTE $REPOSITORY
git fetch $REMOTE

echo "Updating .git/authors.txt"
cd ".git"
curl "http://git.apache.org/authors.txt" > authors.txt
cd ".."
git config svn.authorsfile ".git/authors.txt"

echo "svn init"
git svn init --prefix="$REMOTE/" --tags=tags --trunk=trunk --branches=branches https://svn.apache.org/repos/asf/"$PROJECT_NAME"

echo "svn rebase"
git checkout trunk
git svn rebase

echo "Done initializing git repository"
git checkout master
