# Releasing the pipe-build-lib

- Review and merge a new feature into the `develop` branch.
- Check out the `main` and `develop` branch,
  to make sure these long-lived branches are up to date:
    - `git fetch origin`
    - `git switch main && git pull`
    - `git switch develop && git pull`
- Prepare the git flow with `git flow init -d`
    - this is only required once after creating a local clone of the repository.
- Start git flow release, e.g. `git flow release start v1.6.0`.
- Update the pom.xml to the new version, then commit.
- Finish release, e.g. `git flow release finish -s v1.6.0`.
- Push the changes to the remote repository:
    - `git push origin main`
    - `git push origin develop`
    - `git push origin --tags`
- Create a new GitHub release: https://github.com/cloudogu/pipe-build-lib
- To mark the release for usage in Jenkins, set the `Default version`
  for Library `pipe-build-lib` to the tag of the release:
  https://ecosystem.cloudogu.com/jenkins/manage/configure
  (Requires Jenkins admin permissions)
