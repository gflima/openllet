// Copyright (c) 2006 - 2008, Clark & Parsia, LLC. <http://www.clarkparsia.com>
// This source code is available under the terms of the Affero General Public
// License v3.
//
// Please see LICENSE.txt for full license terms, including the availability of
// proprietary exceptions.
// Questions, comments, or requests for clarification: licensing@clarkparsia.com

package pellet;

import static pellet.PelletCmdOptionArg.NONE;
import static pellet.PelletCmdOptionArg.REQUIRED;

import aterm.ATermAppl;
import com.clarkparsia.owlapi.OntologyUtils;
import com.clarkparsia.pellet.owlapi.OWLAPILoader;
import java.util.HashSet;
import java.util.Set;
import org.mindswap.pellet.KnowledgeBase;
import org.mindswap.pellet.taxonomy.POTaxonomyBuilder;
import org.mindswap.pellet.taxonomy.SubsumptionComparator;
import org.mindswap.pellet.taxonomy.Taxonomy;
import org.mindswap.pellet.taxonomy.printer.ClassTreePrinter;
import org.mindswap.pellet.utils.ATermUtils;
import org.semanticweb.owlapi.model.OWLClass;
import org.semanticweb.owlapi.model.OWLEntity;
import org.semanticweb.owlapi.model.OWLObjectProperty;
import org.semanticweb.owlapi.search.EntitySearcher;

/**
 * <p>
 * Title: PelletTransTree
 * </p>
 * <p>
 * Description: Compute the hierarchy for part-of classes (or individuals) given a (transitive) property.
 * </p>
 * <p>
 * Copyright: Copyright (c) 2008
 * </p>
 * <p>
 * Company: Clark & Parsia, LLC. <http://www.clarkparsia.com>
 * </p>
 *
 * @author Markus Stocker
 */
public class PelletTransTree extends PelletCmdApp
{

	private String propertyName;
	private boolean showClasses;
	private boolean showIndividuals;

	public PelletTransTree()
	{
		super();
	}

	@Override
	public String getAppId()
	{
		return "PelletTransTree: Compute a transitive-tree closure";
	}

	@Override
	public String getAppCmd()
	{
		return "pellet trans-tree " + getMandatoryOptions() + "[options] <file URI>...";
	}

	@Override
	public PelletCmdOptions getOptions()
	{
		showClasses = true;
		showIndividuals = false;

		final PelletCmdOptions options = getGlobalOptions();

		PelletCmdOption option = new PelletCmdOption("property");
		option.setShortOption("p");
		option.setType("<URI>");
		option.setDescription("The part-of (transitive) property");
		option.setIsMandatory(true);
		option.setArg(REQUIRED);
		options.add(option);

		option = new PelletCmdOption("classes");
		option.setShortOption("c");
		option.setDescription("Show parts hierarchy for classes");
		option.setDefaultValue(showClasses);
		option.setIsMandatory(false);
		option.setArg(NONE);
		options.add(option);

		option = new PelletCmdOption("individuals");
		option.setShortOption("i");
		option.setDescription("Show parts hierarchy for individuals");
		option.setDefaultValue(showIndividuals);
		option.setIsMandatory(false);
		option.setArg(NONE);
		options.add(option);

		option = new PelletCmdOption("filter");
		option.setShortOption("f");
		option.setType("<URI>");
		option.setDescription("The class to filter");
		option.setIsMandatory(false);
		option.setArg(REQUIRED);
		options.add(option);

		return options;
	}

	@Override
	public void run()
	{
		propertyName = options.getOption("property").getValueAsString();

		final OWLAPILoader loader = new OWLAPILoader();
		final KnowledgeBase kb = loader.createKB(getInputFiles());

		final OWLEntity entity = OntologyUtils.findEntity(propertyName, loader.getAllOntologies());

		if (entity == null)
			throw new PelletCmdException("Property not found: " + propertyName);

		if (!(entity instanceof OWLObjectProperty))
			throw new PelletCmdException("Not an object property: " + propertyName);

		if (!EntitySearcher.isTransitive((OWLObjectProperty) entity, loader.getAllOntologies().stream()))
			throw new PelletCmdException("Not a transitive property: " + propertyName);

		final ATermAppl p = ATermUtils.makeTermAppl(entity.getIRI().toString());

		ATermAppl c = null;
		boolean filter = false;

		if (options.getOption("filter").exists())
		{
			final String filterName = options.getOption("filter").getValueAsString();
			final OWLEntity filterClass = OntologyUtils.findEntity(filterName, loader.getAllOntologies());
			if (filterClass == null)
				throw new PelletCmdException("Filter class not found: " + filterName);
			if (!(filterClass instanceof OWLClass))
				throw new PelletCmdException("Not a class: " + filterName);

			c = ATermUtils.makeTermAppl(filterClass.getIRI().toString());

			filter = true;
		}

		POTaxonomyBuilder builder = null;

		// Test first the individuals parameter, as per default the --classes
		// option is true
		if (options.getOption("individuals").getValueAsBoolean())
		{
			// Parts for individuals
			builder = new POTaxonomyBuilder(kb, new PartIndividualsComparator(kb, p));

			Set<ATermAppl> individuals;
			if (filter)
				individuals = kb.getInstances(c);
			else
				individuals = kb.getIndividuals(); // Note: this is not an optimal solution

			for (final ATermAppl individual : individuals)
				if (!ATermUtils.isBnode(individual))
					builder.classify(individual);
		}
		else
		{
			builder = new POTaxonomyBuilder(kb, new PartClassesComparator(kb, p));

			if (filter)
				for (final ATermAppl cl : getDistinctSubclasses(kb, c))
					builder.classify(cl);
			else
				builder.classify();
		}

		final Taxonomy<ATermAppl> taxonomy = builder.getTaxonomy();

		final ClassTreePrinter printer = new ClassTreePrinter();
		printer.print(taxonomy);

		publicTaxonomy = taxonomy;
	}

	/** Unit testing access only */
	public Taxonomy<ATermAppl> publicTaxonomy;

	private Set<ATermAppl> getDistinctSubclasses(final KnowledgeBase kb, final ATermAppl c)
	{
		final Set<ATermAppl> filteredClasses = new HashSet<>();
		final Set<Set<ATermAppl>> subclasses = kb.getSubClasses(c);
		for (final Set<ATermAppl> s : subclasses)
			filteredClasses.addAll(s);
		filteredClasses.add(c);

		//Remove not(TOP), since taxonomy builder complains otherwise...
		filteredClasses.remove(ATermUtils.negate(ATermUtils.TOP));

		return filteredClasses;
	}

	private static class PartClassesComparator extends SubsumptionComparator
	{

		private final ATermAppl p;

		public PartClassesComparator(final KnowledgeBase kb, final ATermAppl p)
		{
			super(kb);
			this.p = p;
		}

		@Override
		protected boolean isSubsumedBy(final ATermAppl a, final ATermAppl b)
		{
			final ATermAppl someB = ATermUtils.makeSomeValues(p, b);

			return _kb.isSubClassOf(a, someB);
		}
	}

	private static class PartIndividualsComparator extends SubsumptionComparator
	{

		private final ATermAppl p;

		public PartIndividualsComparator(final KnowledgeBase kb, final ATermAppl p)
		{
			super(kb);
			this.p = p;
		}

		@Override
		protected boolean isSubsumedBy(final ATermAppl a, final ATermAppl b)
		{
			return _kb.hasPropertyValue(a, p, b);
		}
	}
}
