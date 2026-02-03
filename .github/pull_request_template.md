**PLEASE READ THIS**

I acknowledge that:

- [ ] All relevant issues have been mentioned in the PR body (e.g. "Close #???")
- [ ] Have built this library with that changes to update the number of available sources in [Summary](./summary.yaml) file
- [ ] Removed `Broken` annotation if the problem causing it to malfunction has been resolved
- [ ] Added special type to `MangaSourceParser` annotation if necessary (e.g. "`type = ContentType.HENTAI`")
- [ ] Added special language code to `MangaSourceParser` if that source is not a multilingual source (e.g. "`vi`")
- [ ] Have not changed parser class name. Made sure the class name did not conflict with an existing class
- [ ] Have declared all orders, filters that are written in `availableSortOrders`, `filterCapabilities` and `getFilterOptions` must be written in `getList()` OR `getListPage()` function
- [ ] Have followed the instructions and parser class skeleton in this [CONTRIBUTING](../CONTRIBUTING.md) guide

**TESTING**

I acknowledge that:

- [ ] Have tested that parser syntax error by compiling that class with this library
- [ ] Have tested that parser by compiling, building and packing your parsers with the debug application
- [ ] All orders, filters declared in `availableSortOrders`, `filterCapabilities` and `getFilterOptions` work in the debug application
- [ ] `getList()` OR `getListPage()` and `getDetails()` functions have received all the necessary information
- [ ] `getPages()` function has retrieved all images from the source

Note that you must provide accurate information before creating a pull request. This information will be compared with data received from source by reviewers.

---

### Source Name

???

### Related Issues

???

### Description

???
