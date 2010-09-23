PROJECT_NAME=karaf

echo "Updating .git/authors.txt"
cd ".git"
curl "http://git.apache.org/authors.txt" > authors.txt
cd ".."
git config svn.authorsfile ".git/authors.txt"
echo "svn init"
git svn init --prefix=origin/ --tags=tags --trunk=trunk --branches=branches https://svn.apache.org/repos/asf/"$PROJECT_NAME"
echo "svn fetch"
git svn fetch