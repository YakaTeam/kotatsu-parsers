**PLEASE READ THIS**

I confirm that:

- [ ] All relevant issues have been mentioned in the PR description (e.g., "Closes #???")
- [ ] I have built the library with these changes to update the number of available sources in the [Summary](https://github.com/YakaTeam/kotatsu-parsers/blob/master/.github/summary.yaml) file
- [ ] I have removed `@Broken` annotation if the issue causing it to malfunction has been resolved
- [ ] I have added a special type to `MangaSourceParser` annotation if necessary (e.g., "`type = ContentType.HENTAI`")
- [ ] I have added a specific language code to `MangaSourceParser` annotation if the source is not multilingual (e.g., `vi`)
- [ ] I have not changed the parser class name and ensured it does not conflict with any existing class
- [ ] All declared sort orders and filters in `availableSortOrders`, `filterCapabilities`, and `getFilterOptions` are properly implemented in `getList()` OR `getListPage()` function
- [ ] I have followed the instructions and parser class skeleton provided in [this CONTRIBUTING guide](https://github.com/YakaTeam/kotatsu-parsers/blob/master/CONTRIBUTING.md)

**TESTING**

I confirm that:

- [ ] I have tested the parser for syntax errors by compiling the class with the library
- [ ] I have tested the parser by compiling, building, and packaging it with the debug application
- [ ] All sort orders and filters declared in `availableSortOrders`, `filterCapabilities`, and `getFilterOptions` work correctly in the debug application
- [ ] `getList()` OR `getListPage()` and `getDetails()` functions retrieve all necessary information
- [ ] `getPages()` function successfully retrieves all images from the source

**Note:** You must provide accurate information before creating a pull request. This information will be verified against data retrieved from the source by the reviewers.

---

### Source Name (Language)

N/A

### Related Issues

N/A

### Description

N/A
