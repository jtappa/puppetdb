---
title: "PuppetDB 3.0 » Known Issues"
layout: default
canonical: "/puppetdb/latest/known_issues.html"
---


Bugs and Feature Requests
-----

[tracker]: https://tickets.puppetlabs.com/browse/PDB

PuppetDB's bugs and feature requests are managed in [Puppet Labs's issue tracker][tracker]. Search this database if you're having problems and please report any new issues to us!

Broader Issues
-----

### Autorequire relationships are opaque

Puppet resource types can "autorequire" other resources when certain conditions are met but we don't correctly model these relationships in PuppetDB. (For example, if you manage two file resources where one is a parent directory of the other, Puppet will automatically make the child dependent on the parent.) The problem is that these dependencies are not written to the catalog; puppet agent creates these relationships on the fly when it reads the catalog. Getting these relationships into PuppetDB will require a significant change to Puppet's core.
